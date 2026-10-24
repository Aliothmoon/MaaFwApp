package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.ITextInputSink
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.project.PiInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MaaFrameworkRunnerPortTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    // 同 bindRunnerCallback：JVM 单测构造不了 AIDL Stub，只拿接口本身验注册
    private val textInputSink = mockk<ITextInputSink>()

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
        every { MaaDispatchers.Default } returns dispatcher
        mockkObject(AppPaths)
        val root = temp.newFolder("external")
        every { AppPaths.ROOT } returns root
        every { AppPaths.LOG_DIR } returns File(root, "log").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
        unmockkObject(AppPaths)
    }

    private fun plan() = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = listOf(RuntimeTask("启动游戏", "Start", emptyList())),
    )

    private fun twoTaskPlan() = plan().copy(
        tasks = listOf(
            RuntimeTask("启动游戏", "Start", emptyList(), label = "启动"),
            RuntimeTask("领取奖励", "Reward", emptyList(), label = "领奖"),
        ),
    )

    private fun TestScope.recordCallbacks(
        runner: MaaFrameworkRunnerPort,
    ): Pair<MutableList<MaaFrameworkRunnerPort.ExecutionCallback>, MutableList<RunnerEventEnvelope>> {
        val callbacks = mutableListOf<MaaFrameworkRunnerPort.ExecutionCallback>()
        val events = mutableListOf<RunnerEventEnvelope>()
        runner.bindRunnerCallback = { _, callback -> callbacks += callback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { events += it }
        }
        return callbacks to events
    }

    private fun port(
        scope: TestScope,
        service: FakePrivilegedService = FakePrivilegedService(),
        servicePort: FakePrivilegedServicePort = FakePrivilegedServicePort(service),
        saveOnError: () -> Boolean = { true },
        resolutionPreset: () -> ResolutionPreset = { ResolutionPresets.builtIn.first() },
        debugMode: () -> Boolean = { false },
    ): Pair<MaaFrameworkRunnerPort, FakePrivilegedServicePort> {
        val installer = mockk<PiInstaller>()
        every { installer.installedDir() } returns temp.newFolder("pi")
        val runner = MaaFrameworkRunnerPort(
            installer = installer,
            apkPath = "/apk",
            nativeLibraryDir = "/lib",
            runMode = { RunMode.BACKGROUND },
            resolutionPreset = resolutionPreset,
            debugMode = debugMode,
            saveOnError = saveOnError,
            scope = scope.backgroundScope,
            servicePort = servicePort,
            textInputSink = { textInputSink },
        )
        // JVM 单测构造不了 AIDL Stub；本文件只测 phase，不测回调转发
        runner.bindRunnerCallback = { _, _ -> }
        return runner to servicePort
    }

    @Test
    fun `stop during prepare is retried after start returns`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort(service).apply { holdUseService = hold }
        val (runner, _) = port(this, service, servicePort)

        val started = async { runner.start(plan(), "e1") }
        advanceUntilIdle()
        assertEquals(RunnerPhase.Preparing, runner.state.value.phase)

        assertEquals(RunnerCommandResult.Accepted, runner.stop())
        assertEquals(RunnerPhase.Stopping, runner.state.value.phase)

        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals(RunnerCommandResult.Accepted, started.await())
        assertEquals(RunnerPhase.Stopping, runner.state.value.phase)
        assertEquals(2, service.stopRunCount)
    }

    @Test
    fun `saveOnError is read per run and pushed to the service`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val (runner, _) = port(this, service, saveOnError = { false })

        assertEquals(RunnerCommandResult.Accepted, runner.start(plan(), "e1"))
        advanceUntilIdle()

        assertFalse(service.saveOnError)
    }

    /** dpi 跟着预设走，不再是写死的常量 */
    @Test
    fun `background run builds the virtual display from the selected preset`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val preset = ResolutionPresets.builtIn[1]
        val (runner, _) = port(this, service, resolutionPreset = { preset })

        assertEquals(RunnerCommandResult.Accepted, runner.start(plan(), "e1"))
        advanceUntilIdle()

        assertEquals(Triple(1920, 1080, 280), service.lastVirtualDisplayResolution)
    }

    /** 对齐 MaaPiCli：先 resource.path，再 controller.attach_resource_path */
    @Test
    fun `controller attach resource paths are loaded after resource paths`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val (runner, _) = port(this, service)
        val attached = plan().copy(
            controller = ControllerDefinition(attachResourcePaths = listOf("resource_adb")),
        )

        assertEquals(RunnerCommandResult.Accepted, runner.start(attached, "e1"))
        advanceUntilIdle()

        val payload = runPlanWireJson.decodeFromString(RunPlanPayload.serializer(), service.lastRunPlanJson!!)
        val piRoot = File(temp.root, "pi")
        assertEquals(
            listOf(File(piRoot, "./base").absolutePath, File(piRoot, "resource_adb").absolutePath),
            payload.resourcePaths,
        )
    }

    /** 特权进程可能在两轮之间重启过，sink 要每轮都注册，不能只在绑定时注册一次 */
    @Test
    fun `text input sink is registered on every run`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val (runner, _) = port(this, service)

        assertEquals(RunnerCommandResult.Accepted, runner.start(plan(), "e1"))
        advanceUntilIdle()

        assertTrue(service.textInputSink === textInputSink)
    }

    @Test
    fun `start rejection during Stopping returns to Idle`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort(service).apply { holdUseService = hold }
        val (runner, _) = port(this, service, servicePort)

        val started = async { runner.start(plan(), "e1") }
        advanceUntilIdle()
        assertEquals(RunnerPhase.Preparing, runner.state.value.phase)

        assertEquals(RunnerCommandResult.Accepted, runner.stop())
        service.startRunResult = false
        hold.complete(Unit)
        advanceUntilIdle()

        assertTrue(started.await() is RunnerCommandResult.Rejected)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertTrue(runner.state.value.latestResult is ExecutionResult.Cancelled)
    }

    @Test
    fun `cancellation during prepare rethrows and returns to Idle`() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort().apply { holdUseService = hold }
        val (runner, _) = port(this, servicePort = servicePort)

        val started = async { runner.start(plan(), "e1") }
        advanceUntilIdle()
        assertEquals(RunnerPhase.Preparing, runner.state.value.phase)

        started.cancel()
        advanceUntilIdle()

        try {
            started.await()
            error("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertTrue(runner.state.value.latestResult is ExecutionResult.Failed)
    }

    @Test
    fun `events carry the task that was current when they arrived`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val (callbacks, events) = recordCallbacks(runner)

        runner.start(twoTaskPlan(), "e1")
        val callback = callbacks.single()
        callback.onTaskStarted("启动游戏", 0, 2)
        callback.onEvent("Tasker.Task.Failed", """{"entry":"Start"}""")
        callback.onTaskStarted("领取奖励", 1, 2)

        val failed = events.single { (it.event as? RunnerEvent.Callback)?.message == "Tasker.Task.Failed" }
        assertEquals("e1", failed.executionId)
        assertEquals("启动", failed.taskLabel)
        assertEquals("领取奖励", runner.state.value.activeExecution?.currentTaskName)
    }

    @Test
    fun `a task cut short by stop is marked cancelled on its finish marker`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val (callbacks, events) = recordCallbacks(runner)

        runner.start(twoTaskPlan(), "e1")
        val callback = callbacks.single()
        callback.onTaskStarted("启动游戏", 0, 2)
        callback.onTaskFinished("启动游戏", false, "Failed", true)

        val finished = events.single { it.event is RunnerEvent.TaskFinished }.event
        assertEquals(RunnerEvent.TaskFinished(0, success = false, cancelled = true), finished)
    }

    @Test
    fun `the terminal marker is emitted before state turns idle`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val (callbacks, events) = recordCallbacks(runner)
        var phaseAtMarker: RunnerPhase? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect {
                if (it.event is RunnerEvent.ExecutionFinished) phaseAtMarker = runner.state.value.phase
            }
        }

        runner.start(plan(), "e1")
        callbacks.single().onFinished(RunOutcome.COMPLETED, null)

        assertEquals(RunnerPhase.Running, phaseAtMarker)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertEquals(listOf("e1"), events.filter { it.event is RunnerEvent.ExecutionFinished }.map { it.executionId })
    }

    /** 测试用的 APK 路径读不到描述，名字退到 agent[n] */
    @Test
    fun `an agent launch failure names the agent and points at debug mode`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val (callbacks, _) = recordCallbacks(runner)

        runner.start(plan(), "e1")
        callbacks.single().onFinished(RunOutcome.AGENT_LAUNCH_FAILED, "1")

        assertEquals(
            uiTextOf(R.string.msg_fail_debug_hint, uiTextOf(R.string.msg_fail_agent_launch, "agent[1]")),
            (runner.state.value.latestResult as ExecutionResult.Failed).reason,
        )
    }

    @Test
    fun `an agent launch failure drops the hint once debug mode is on`() = runTest(dispatcher) {
        var debug = false
        val (runner, _) = port(this, debugMode = { debug })
        val (callbacks, _) = recordCallbacks(runner)

        runner.start(plan(), "e1")
        debug = true
        callbacks.single().onFinished(RunOutcome.AGENT_LAUNCH_FAILED, "")

        assertEquals(
            uiTextOf(R.string.msg_fail_agent_launch_any),
            (runner.state.value.latestResult as ExecutionResult.Failed).reason,
        )
    }

    @Test
    fun `a callback from the previous run cannot touch the next one`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val (callbacks, events) = recordCallbacks(runner)

        runner.start(plan(), "e1")
        val stale = callbacks.single()
        stale.onFinished(RunOutcome.COMPLETED, null)
        runner.start(plan(), "e2")

        stale.onEvent("Tasker.Task.Failed", """{"entry":"Start"}""")
        stale.onTaskFinished("启动游戏", false, "late", false)
        stale.onFinished(RunOutcome.FAILED, "late")

        val active = runner.state.value.activeExecution
        assertEquals("e2", active?.executionId)
        assertEquals(emptyList<TaskResult>(), active?.taskResults)
        assertEquals(RunnerPhase.Running, runner.state.value.phase)
        assertEquals("e1", events.last().executionId)
        assertEquals(1, events.count { it.event is RunnerEvent.ExecutionFinished })
    }

    @Test
    fun `an aborted run still emits its terminal marker`() = runTest(dispatcher) {
        val servicePort = FakePrivilegedServicePort()
        val (runner, _) = port(this, servicePort = servicePort)
        val (_, events) = recordCallbacks(runner)

        runner.start(plan(), "e1")
        servicePort.emit(PrivilegedServiceState.Died)
        repeat(4) { testScheduler.runCurrent() }

        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertEquals(listOf("e1"), events.filter { it.event is RunnerEvent.ExecutionFinished }.map { it.executionId })
    }
}
