package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.runner.ConfirmToken
import com.aliothmoon.maafw.runner.RunLaunchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleLaunchOutcomeTest {

    @Test
    fun `started records no failure reason`() {
        val outcome = RunLaunchResult.Started.toScheduleOutcome()

        assertEquals(TriggerResult.STARTED, outcome.result)
        assertNull(outcome.failureReason)
    }

    @Test
    fun `every non-started result carries a reason`() {
        val results = listOf(
            RunLaunchResult.ProjectNotReady to TriggerFailureReason.PROJECT_NOT_READY,
            RunLaunchResult.ConfigurationMissing to TriggerFailureReason.CONFIGURATION_MISSING,
            RunLaunchResult.NoExecutableTasks to TriggerFailureReason.NO_EXECUTABLE_TASKS,
            RunLaunchResult.Invalid(emptyList()) to TriggerFailureReason.INVALID_PLAN,
            RunLaunchResult.Rejected(uiTextFromFramework("busy")) to TriggerFailureReason.REJECTED,
            RunLaunchResult.Blocked(uiTextOf(R.string.msg_no_executable_tasks)) to
                TriggerFailureReason.BLOCKED,
        )

        for ((result, expected) in results) {
            val outcome = result.toScheduleOutcome()
            assertEquals(result.toString(), TriggerResult.FAILED_START, outcome.result)
            assertEquals(result.toString(), expected, outcome.failureReason)
        }
    }

    /** 定时触发本该在 RunLauncher 里被降级；真漏过来也得记成失败而不是当成功 */
    @Test
    fun `needs confirmation is recorded as blocked rather than started`() {
        val outcome = RunLaunchResult
            .NeedsConfirmation(ConfirmToken("t"), uiTextOf(R.string.msg_no_executable_tasks))
            .toScheduleOutcome()

        assertEquals(TriggerResult.FAILED_START, outcome.result)
        assertEquals(TriggerFailureReason.BLOCKED, outcome.failureReason)
    }
}
