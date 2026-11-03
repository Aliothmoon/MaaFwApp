package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.privileged.WatchdogState
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogNoticeHookTest {

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    private class RecordingJournal : RunJournal {
        val notes = mutableListOf<Pair<RunNote, UiText>>()

        override suspend fun begin(plan: RunPlan, executionId: String) = Unit
        override suspend fun end(executionId: String, reason: RunEndReason) = Unit

        override fun note(executionId: String, level: RunNote, text: UiText) {
            notes += level to text
        }
    }

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    @Test
    fun `app death is logged as an error and stops the run`() = runTest(UnconfinedTestDispatcher()) {
        val watchdog = MutableStateFlow(WatchdogState.WATCHING)
        val service = FakePrivilegedService().apply { watchdogTargetPackageValue = "com.example.game" }
        val runner = RecordingEventRunnerPort()
        val journal = RecordingJournal()
        var notifications = 0
        val rememberedPackages = mutableListOf<String>()
        val recordedPackages = mutableListOf<String>()
        val hook = WatchdogNoticeHook(
            watchdogState = watchdog,
            servicePort = FakePrivilegedServicePort(service),
            runnerPort = runner,
            notifyGameGone = { notifications++ },
            rememberGamePackage = { rememberedPackages += it },
            recordGameExit = { recordedPackages += it },
            journal = journal,
            scope = backgroundScope,
        )
        val context = RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = journal)

        hook.engage(context)
        watchdog.value = WatchdogState.APP_DIED
        runCurrent()

        assertEquals(listOf(RunNote.Error), journal.notes.map { it.first })
        assertEquals(1, runner.stopCount)
        assertEquals(1, notifications)
        assertEquals(listOf("com.example.game"), rememberedPackages)
        assertEquals(listOf("com.example.game"), recordedPackages)
    }

    @Test
    fun `display drift stays a warning and does not stop the run`() = runTest(UnconfinedTestDispatcher()) {
        val watchdog = MutableStateFlow(WatchdogState.WATCHING)
        val journalist = RecordingJournal()
        val runner = RecordingEventRunnerPort()
        var notifications = 0
        val hook = WatchdogNoticeHook(
            watchdogState = watchdog,
            servicePort = FakePrivilegedServicePort(),
            runnerPort = runner,
            notifyGameGone = { notifications++ },
            rememberGamePackage = {},
            recordGameExit = {},
            journal = journalist,
            scope = backgroundScope,
        )
        val context = RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = journalist)

        hook.engage(context)
        watchdog.value = WatchdogState.DISPLAY_DRIFT
        runCurrent()

        assertEquals(listOf(RunNote.Warning), journalist.notes.map { it.first })
        assertEquals(0, runner.stopCount)
        assertEquals(0, notifications)
    }
}
