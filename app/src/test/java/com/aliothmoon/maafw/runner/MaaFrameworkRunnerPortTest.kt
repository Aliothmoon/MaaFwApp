package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
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
import org.junit.Assert.assertNull
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

    private fun labeledPlan(label: String) = plan().copy(
        tasks = listOf(RuntimeTask("启动游戏", "Start", emptyList(), label = label)),
    )

    private fun duplicateTaskPlan() = plan().copy(
        tasks = listOf(
            RuntimeTask("启动游戏", "Start", emptyList(), label = "第一次"),
            RuntimeTask("启动游戏", "Start", emptyList(), label = "第二次"),
        ),
    )

    private fun port(
        scope: TestScope,
        service: FakePrivilegedService = FakePrivilegedService(),
        servicePort: FakePrivilegedServicePort = FakePrivilegedServicePort(service),
    ): Pair<MaaFrameworkRunnerPort, FakePrivilegedServicePort> {
        val installer = mockk<PiInstaller>()
        every { installer.installedDir() } returns temp.newFolder("pi")
        val runner = MaaFrameworkRunnerPort(
            installer = installer,
            apkPath = "/apk",
            nativeLibraryDir = "/lib",
            runMode = { RunMode.BACKGROUND },
            resolutionPreference = { ResolutionPreference.P720 },
            debugMode = { false },
            scope = scope.backgroundScope,
            servicePort = servicePort,
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

        val started = async { runner.start(plan(), "execution-1") }
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
    fun `a stale callback keeps its execution context and cannot mutate the next run`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val callbacks = mutableListOf<MaaFrameworkRunnerPort.ExecutionCallback>()
        val events = mutableListOf<RunnerEventEnvelope>()
        runner.bindRunnerCallback = { _, callback -> callbacks += callback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { events += it }
        }
        advanceUntilIdle()

        runner.start(labeledPlan("旧任务"), "execution-1")
        val oldCallback = callbacks.single()
        oldCallback.onTaskStarted("回调别名", 0, 1)
        oldCallback.onFinished(RunOutcome.COMPLETED, null)
        advanceUntilIdle()

        runner.start(labeledPlan("新任务"), "execution-2")
        oldCallback.onTaskStarted("回调别名", 0, 1)
        oldCallback.onTaskFinished("启动游戏", false, "late")
        oldCallback.onFinished(RunOutcome.COMPLETED, null)
        advanceUntilIdle()

        val active = runner.state.value.activeExecution
        assertEquals("execution-2", active?.executionId)
        assertNull(active?.currentTaskName)
        assertEquals(emptyList<TaskResult>(), active?.taskResults)
        assertEquals(RunnerPhase.Running, runner.state.value.phase)

        val progress = events.filterIsInstance<RunnerEventEnvelope>()
            .mapNotNull { it.event as? RunnerEvent.Progress }
        assertEquals(listOf("旧任务", "旧任务"), progress.map { it.taskLabel })
        assertEquals(listOf("execution-1", "execution-1", "execution-1"), events.map { it.executionId })
    }

    @Test
    fun `the terminal marker is emitted before state becomes idle`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val callbacks = mutableListOf<MaaFrameworkRunnerPort.ExecutionCallback>()
        val events = mutableListOf<RunnerEventEnvelope>()
        var phaseAtTerminal: RunnerPhase? = null
        runner.bindRunnerCallback = { _, callback -> callbacks += callback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { envelope ->
                if (envelope.event is RunnerEvent.ExecutionFinished) {
                    phaseAtTerminal = runner.state.value.phase
                }
                events += envelope
            }
        }
        advanceUntilIdle()

        runner.start(labeledPlan("旧任务"), "execution-1")
        callbacks.single().onFinished(RunOutcome.COMPLETED_WITH_FAILURES, null)

        assertEquals(RunnerPhase.Running, phaseAtTerminal)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertEquals("execution-1", events.single().executionId)
        assertTrue(events.single().event is RunnerEvent.ExecutionFinished)
    }

    @Test
    fun `forced abort emits the terminal marker for its execution`() = runTest(dispatcher) {
        val servicePort = FakePrivilegedServicePort()
        val (runner, _) = port(this, servicePort = servicePort)
        val events = mutableListOf<RunnerEventEnvelope>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { events += it }
        }
        advanceUntilIdle()

        runner.start(labeledPlan("当前任务"), "execution-1")
        servicePort.emit(PrivilegedServiceState.Died)
        repeat(4) { testScheduler.runCurrent() }

        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
        assertEquals("execution-1", runner.state.value.latestExecutionId)
        assertEquals(
            listOf("execution-1"),
            events
                .filter { it.event is RunnerEvent.ExecutionFinished }
                .map { it.executionId },
        )
    }

    @Test
    fun `duplicate task names keep the label of the started instance`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val callbacks = mutableListOf<MaaFrameworkRunnerPort.ExecutionCallback>()
        val events = mutableListOf<RunnerEventEnvelope>()
        runner.bindRunnerCallback = { _, callback -> callbacks += callback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { events += it }
        }
        advanceUntilIdle()

        runner.start(duplicateTaskPlan(), "execution-1")
        val callback = callbacks.single()
        callback.onTaskStarted("回调别名", 0, 2)
        assertEquals("第一次", runner.state.value.activeExecution?.currentTaskLabel)

        callback.onTaskStarted("回调别名", 1, 2)
        assertEquals("第二次", runner.state.value.activeExecution?.currentTaskLabel)

        val progress = events.mapNotNull { it.event as? RunnerEvent.Progress }
        assertEquals(listOf("第一次", "第二次"), progress.map { it.taskLabel })
        assertEquals(
            listOf("第一次", "第二次"),
            events.mapNotNull { it.currentTaskLabel },
        )
    }

    @Test
    fun `blank task labels fall back to the task name in progress`() = runTest(dispatcher) {
        val (runner, _) = port(this)
        val callbacks = mutableListOf<MaaFrameworkRunnerPort.ExecutionCallback>()
        val events = mutableListOf<RunnerEventEnvelope>()
        runner.bindRunnerCallback = { _, callback -> callbacks += callback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.events.collect { events += it }
        }
        advanceUntilIdle()

        runner.start(labeledPlan(""), "execution-1")
        callbacks.single().onTaskStarted("回调别名", 0, 1)

        val progress = events.mapNotNull { it.event as? RunnerEvent.Progress }
        assertEquals(listOf("启动游戏"), progress.map { it.taskLabel })
    }

    @Test
    fun `start rejection during Stopping returns to Idle`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort(service).apply { holdUseService = hold }
        val (runner, _) = port(this, service, servicePort)

        val started = async { runner.start(plan(), "execution-1") }
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

        val started = async { runner.start(plan(), "execution-1") }
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
}
