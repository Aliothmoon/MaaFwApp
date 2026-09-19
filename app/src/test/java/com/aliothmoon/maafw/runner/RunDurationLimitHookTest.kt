package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunDurationLimitHookTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private class RecordingRunJournal : RunJournal {
        val warnings = mutableListOf<UiText>()

        override suspend fun begin(plan: RunPlan) = Unit

        override suspend fun end(reason: RunEndReason) = Unit

        override fun note(level: RunNote, text: UiText) {
            if (level == RunNote.Warning) warnings += text
        }
    }

    private class RecordingRunner : RunnerPort {
        private val _state = MutableStateFlow(RunnerState(phase = RunnerPhase.Running))
        override val state: StateFlow<RunnerState> = _state.asStateFlow()

        val stopCalls = mutableListOf<Int>()

        override val events: Flow<RunnerEvent> get() = throw AssertionError("not used")

        override suspend fun start(plan: RunPlan): RunnerCommandResult =
            throw AssertionError("not used")

        override suspend fun stop(): RunnerCommandResult {
            stopCalls += 1
            _state.value = RunnerState(
                phase = RunnerPhase.Idle,
                latestResult = ExecutionResult.Cancelled(emptyList()),
            )
            return RunnerCommandResult.Accepted
        }
    }

    @Test
    fun `limit is skipped when disabled`() = runTest(testDispatcher) {
        val hook = RunDurationLimitHook(
            settings = FakeAppSettingsGateway(),
            runnerPort = StubRunnerPort(backgroundScope),
            journal = DiscardingRunJournal,
            scope = backgroundScope,
        )

        assertTrue(hook.engage(context()) is EngageResult.Skipped)
    }

    @Test
    fun `timer stops the run and records the limit`() = runTest(testDispatcher) {
        val runner = RecordingRunner()
        val journal = RecordingRunJournal()
        val settings = FakeAppSettingsGateway().apply {
            runDurationLimitEnabled.value = true
            runDurationLimitMinutes.value = 1
        }
        val hook = RunDurationLimitHook(settings, runner, journal, backgroundScope)
        val result = hook.engage(context())

        assertTrue(result is EngageResult.Engaged)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(1), runner.stopCalls)
        assertEquals(1, journal.warnings.size)

        (result as EngageResult.Engaged).release(
            RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())),
        )
    }

    @Test
    fun `release cancels a timer that has not fired`() = runTest(testDispatcher) {
        val runner = RecordingRunner()
        val settings = FakeAppSettingsGateway().apply {
            runDurationLimitEnabled.value = true
            runDurationLimitMinutes.value = 1
        }
        val hook = RunDurationLimitHook(settings, runner, DiscardingRunJournal, backgroundScope)
        val result = hook.engage(context()) as EngageResult.Engaged

        result.release(RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())))
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(runner.stopCalls.isEmpty())
    }

    private fun context() =
        RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = DiscardingRunJournal)
}
