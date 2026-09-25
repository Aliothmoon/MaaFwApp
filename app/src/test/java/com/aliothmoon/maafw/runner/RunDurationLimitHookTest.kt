package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunDurationLimitHookTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private class RecordingRunJournal : RunJournal {
        val notes = mutableListOf<Pair<RunNote, UiText>>()

        override suspend fun begin(plan: RunPlan, executionId: String) = Unit

        override suspend fun end(executionId: String, reason: RunEndReason) = Unit

        override fun note(executionId: String, level: RunNote, text: UiText) {
            notes += level to text
        }
    }

    private val journal = RecordingRunJournal()
    private val ctx = RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = journal)

    /** 把 runner 置成正在跑 [executionId] 那一轮 */
    private fun runnerRunning(executionId: String = ctx.executionId) = RecordingEventRunnerPort().apply {
        setState(
            RunnerState(
                phase = RunnerPhase.Running,
                activeExecution = ActiveExecution(
                    executionId = executionId,
                    runConfigurationId = plan.runConfigurationId,
                    currentTaskName = null,
                    completedTaskCount = 0,
                    totalTaskCount = 0,
                    taskResults = emptyList(),
                ),
            ),
        )
    }

    private fun limitSettings(minutes: Int = 1) = FakeAppSettingsGateway().apply {
        runDurationLimitEnabled.value = true
        runDurationLimitMinutes.value = minutes
    }

    private fun hook(
        settings: FakeAppSettingsGateway,
        runner: RunnerPort,
        scope: CoroutineScope,
    ) = RunDurationLimitHook(settings, runner, scope)

    @Test
    fun `limit is skipped when disabled`() = runTest {
        val runner = runnerRunning()

        assertTrue(hook(FakeAppSettingsGateway(), runner, backgroundScope).engage(ctx) is EngageResult.Skipped)
        advanceTimeBy(RunDurationLimit.MAX_MINUTES * 60_000L + 1)
        assertEquals(0, runner.stopCount)
    }

    /** 停下之后会话文件随时会关，那行得在 stop 之前落下 */
    @Test
    fun `timer logs the limit before stopping the run`() = runTest {
        val runner = runnerRunning()
        var notesAtStop = -1
        runner.onStop = {
            notesAtStop = journal.notes.size
            RunnerCommandResult.Accepted
        }
        hook(limitSettings(), runner, backgroundScope).engage(ctx)

        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(0, runner.stopCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runner.stopCount)
        assertEquals(1, notesAtStop)
        assertEquals(RunNote.Warning, journal.notes.single().first)
        assertTrue(ctx.stoppedAtLimit.get())
    }

    @Test
    fun `release cancels a timer that has not fired`() = runTest {
        val runner = runnerRunning()
        val result = hook(limitSettings(), runner, backgroundScope).engage(ctx) as EngageResult.Engaged

        result.release(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(0, runner.stopCount)
        assertFalse(ctx.stoppedAtLimit.get())
    }

    @Test
    fun `minutes are frozen at engage`() = runTest {
        val runner = runnerRunning()
        val settings = limitSettings(minutes = 1)
        hook(settings, runner, backgroundScope).engage(ctx)

        settings.runDurationLimitMinutes.value = RunDurationLimit.MAX_MINUTES
        settings.runDurationLimitEnabled.value = false
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(1, runner.stopCount)
    }

    /** stop 不带轮次：本轮刚跑完、下一轮已受理时，到点的旧计时器不能把新一轮停掉 */
    @Test
    fun `timer leaves a later execution alone`() = runTest {
        val runner = runnerRunning(executionId = "next-round")
        hook(limitSettings(), runner, backgroundScope).engage(ctx)

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(0, runner.stopCount)
        assertTrue(journal.notes.isEmpty())
        assertFalse(ctx.stoppedAtLimit.get())
    }

    @Test
    fun `a rejected stop clears the mark and logs the failure`() = runTest {
        val runner = runnerRunning()
        runner.onStop = { RunnerCommandResult.Rejected(uiTextFormatted("ipc down")) }
        hook(limitSettings(), runner, backgroundScope).engage(ctx)

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(listOf(RunNote.Warning, RunNote.Error), journal.notes.map { it.first })
        assertFalse(ctx.stoppedAtLimit.get())
    }

    @Test
    fun `stored minutes outside the range fall back to the default`() {
        assertEquals(90, RunDurationLimit.parse("90"))
        assertEquals(RunDurationLimit.MIN_MINUTES, RunDurationLimit.parse("${RunDurationLimit.MIN_MINUTES}"))
        assertEquals(RunDurationLimit.MAX_MINUTES, RunDurationLimit.parse("${RunDurationLimit.MAX_MINUTES}"))
        listOf("", "abc", "0", "-5", "1441", "99999").forEach { raw ->
            assertEquals(raw, RunDurationLimit.DEFAULT_MINUTES, RunDurationLimit.parse(raw))
        }
    }

    @Test
    fun `written minutes are clamped into the range`() {
        assertEquals(RunDurationLimit.MIN_MINUTES, RunDurationLimit.normalize(0))
        assertEquals(RunDurationLimit.MAX_MINUTES, RunDurationLimit.normalize(99999))
        assertEquals(90, RunDurationLimit.normalize(90))
    }
}
