package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogNoticeHookTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    private class RecordingJournal : RunJournal {
        val notes = mutableListOf<Pair<RunNote, UiText.Resource>>()

        override suspend fun begin(plan: RunPlan) = Unit

        override suspend fun end(reason: RunEndReason) = Unit

        override fun note(level: RunNote, text: UiText) {
            if (text is UiText.Resource) notes += level to text
        }
    }

    private fun TestScope.context(journal: RunJournal) = RunContext(
        trigger = RunTrigger.Manual,
        runMode = RunMode.BACKGROUND,
        plan = RunPlan(
            projectName = "demo",
            projectVersion = "1",
            controller = ControllerDefinition(),
            resource = ResourceDefinition("官服", listOf("./base")),
            runConfigurationId = RunConfigurationId("c1"),
            tasks = emptyList(),
        ),
        journal = journal,
    )

    @Test
    fun `empty virtual display warns once and stays quiet after recovery`() = runTest(dispatcher) {
        val state = MutableStateFlow(WatchdogState.IDLE)
        val journal = RecordingJournal()
        val servicePort = FakePrivilegedServicePort(FakePrivilegedService())
        val hook = WatchdogNoticeHook(state, servicePort, journal, this)

        val result = hook.engage(context(journal))
        advanceUntilIdle()
        state.value = WatchdogState.VIRTUAL_DISPLAY_EMPTY
        advanceUntilIdle()
        state.value = WatchdogState.WATCHING
        advanceUntilIdle()

        val engaged = result as EngageResult.Engaged
        engaged.release(RunEndReason.NotRun(NotRunCause.Cancelled))

        assertEquals(
            listOf(RunNote.Warning to R.string.run_log_virtual_display_empty),
            journal.notes.map { (level, text) -> level to text.resId },
        )
    }
}
