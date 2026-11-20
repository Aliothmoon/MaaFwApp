package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogHookTest {

    private class RecordingRunJournal : RunJournal {
        val events = mutableListOf<Any>()

        override suspend fun begin(plan: RunPlan, executionId: String) {
            events += "begin"
        }

        override suspend fun end(executionId: String, reason: RunEndReason) = Unit

        override fun note(executionId: String, level: RunNote, text: UiText) {
            events += level to text
        }
    }

    @Test
    fun `skipped tasks are logged as warnings with their reasons after the session opens`() = runTest {
        val missing = uiTextOf(R.string.task_unavailable_missing)
        val mismatch = uiTextOf(R.string.task_unavailable_controller)
        val plan = RunPlan(
            projectName = "demo",
            projectVersion = "1",
            controller = ControllerDefinition(),
            resource = ResourceDefinition("官服", listOf("./base")),
            runConfigurationId = RunConfigurationId("c1"),
            tasks = emptyList(),
            skippedTasks = listOf(SkippedTask("刷体力", missing), SkippedTask("领奖励", mismatch)),
        )
        val journal = RecordingRunJournal()

        SessionLogHook(journal).engage(RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = journal))

        assertEquals(
            listOf(
                "begin",
                RunNote.Warning to uiTextOf(R.string.run_log_task_skipped, "刷体力", missing),
                RunNote.Warning to uiTextOf(R.string.run_log_task_skipped, "领奖励", mismatch),
            ),
            journal.events,
        )
    }
}
