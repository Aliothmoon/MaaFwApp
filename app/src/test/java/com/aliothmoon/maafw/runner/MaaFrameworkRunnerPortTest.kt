package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.project.PiInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
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

    private fun port(
        scope: TestScope,
        service: FakePrivilegedService = FakePrivilegedService(),
        servicePort: FakePrivilegedServicePort = FakePrivilegedServicePort(service),
        forceRestartApp: () -> Boolean = { false },
    ): Pair<MaaFrameworkRunnerPort, FakePrivilegedServicePort> {
        val installer = mockk<PiInstaller>()
        every { installer.installedDir() } returns temp.newFolder("pi")
        val runner = MaaFrameworkRunnerPort(
            installer = installer,
            apkPath = "/apk",
            nativeLibraryDir = "/lib",
            runMode = { RunMode.BACKGROUND },
            resolutionPreference = { ResolutionPreference.P720 },
            forceRestartApp = forceRestartApp,
            debugMode = { false },
            scope = scope.backgroundScope,
            servicePort = servicePort,
        )
        // JVM 单测构造不了 AIDL Stub；本文件只测 phase，不测回调转发
        runner.bindRunnerCallback = {}
        return runner to servicePort
    }

    @Test
    fun `force restart preference is carried in run payload`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val (runner, _) = port(this, service)

        runner.start(plan())
        advanceUntilIdle()

        val payload = runPlanWireJson.decodeFromString<RunPlanPayload>(service.startRunJsons.single())
        assertFalse(payload.forceRestartApp)
    }

    @Test
    fun `stop during prepare is retried after start returns`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort(service).apply { holdUseService = hold }
        val (runner, _) = port(this, service, servicePort)

        val started = async { runner.start(plan()) }
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
    fun `start rejection during Stopping returns to Idle`() = runTest(dispatcher) {
        val service = FakePrivilegedService()
        val hold = CompletableDeferred<Unit>()
        val servicePort = FakePrivilegedServicePort(service).apply { holdUseService = hold }
        val (runner, _) = port(this, service, servicePort)

        val started = async { runner.start(plan()) }
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

        val started = async { runner.start(plan()) }
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
