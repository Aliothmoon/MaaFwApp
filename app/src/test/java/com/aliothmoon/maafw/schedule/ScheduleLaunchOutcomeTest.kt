package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.ScheduleNotice
import com.aliothmoon.maafw.runner.BlockSource
import com.aliothmoon.maafw.runner.PrivilegedAccessPrecheck
import com.aliothmoon.maafw.runner.PrivilegedConnectHook
import com.aliothmoon.maafw.runner.ConfirmToken
import com.aliothmoon.maafw.runner.NotRunCause
import com.aliothmoon.maafw.runner.RunLaunchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            RunLaunchResult.Blocked(
                uiTextOf(R.string.run_countdown_cancelled),
                BlockSource.Hook("countdown", NotRunCause.Cancelled),
            ) to
                TriggerFailureReason.BLOCKED,
            RunLaunchResult.QueueTimedOut to TriggerFailureReason.EXPIRED,
        )

        for ((result, expected) in results) {
            val outcome = result.toScheduleOutcome()
            assertEquals(result.toString(), TriggerResult.FAILED_START, outcome.result)
            assertEquals(result.toString(), expected, outcome.failureReason)
        }
    }

    /** 失败外推、已在运行算跳过、用户在倒计时里取消的不发；后端没就绪单独一档，好顶替提前提醒 */
    @Test
    fun `notice separates failures, skips, user cancels and backend outages`() {
        val reason = uiTextFromFramework("x")
        val cases = listOf(
            RunLaunchResult.Started to null,
            RunLaunchResult.DuplicateRequest to null,
            RunLaunchResult.ProjectNotReady to ScheduleNotice.FAILED,
            RunLaunchResult.QueueTimedOut to ScheduleNotice.FAILED,
            RunLaunchResult.Rejected(reason) to ScheduleNotice.SKIPPED,
            RunLaunchResult.Blocked(reason, BlockSource.Busy) to ScheduleNotice.SKIPPED,
            RunLaunchResult.Blocked(reason, BlockSource.Hook("countdown", NotRunCause.Cancelled)) to null,
            RunLaunchResult.Blocked(reason, BlockSource.Hook("wake-unlock", NotRunCause.HookFailed)) to
                ScheduleNotice.FAILED,
            RunLaunchResult.Blocked(reason, BlockSource.Hook(PrivilegedConnectHook.ID, NotRunCause.HookFailed)) to
                ScheduleNotice.BACKEND_DOWN,
            RunLaunchResult.Blocked(reason, BlockSource.Precheck(PrivilegedAccessPrecheck.ID)) to
                ScheduleNotice.BACKEND_DOWN,
            RunLaunchResult.Blocked(reason, BlockSource.Precheck("background-mode")) to ScheduleNotice.FAILED,
        )

        for ((result, expected) in cases) {
            assertEquals(result.toString(), expected, result.toScheduleOutcome().notice)
        }
    }

    /** 前置检查拦的要记下是哪一道：触发日志的「去修复」靠它认出后端没就绪 */
    @Test
    fun `precheck block records which check stopped it`() {
        val reason = uiTextFromFramework("x")

        assertEquals(
            PrivilegedAccessPrecheck.ID,
            RunLaunchResult.Blocked(reason, BlockSource.Precheck(PrivilegedAccessPrecheck.ID)).toScheduleOutcome().blockedBy,
        )
        assertNull(
            RunLaunchResult.Blocked(reason, BlockSource.Hook(PrivilegedConnectHook.ID, NotRunCause.HookFailed))
                .toScheduleOutcome().blockedBy,
        )
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

    @Test
    fun `invalid plan carries the first error so the log says which option to fix`() {
        val warning = Diagnostic.warning("global_option", uiTextFromFramework("w"))
        val first = Diagnostic.error("task:A", uiTextFromFramework("e1"))
        val second = Diagnostic.error("task:B", uiTextFromFramework("e2"))

        val outcome = RunLaunchResult.Invalid(listOf(warning, first, second)).toScheduleOutcome()

        assertEquals(TriggerFailureReason.INVALID_PLAN, outcome.failureReason)
        assertEquals(
            uiTextOf(R.string.schedule_reason_invalid_plan_detail, first.source, first.message),
            outcome.detail,
        )
    }

    @Test
    fun `invalid plan without errors falls back to the bare reason`() {
        val warning = Diagnostic.warning("global_option", uiTextFromFramework("w"))

        assertNull(RunLaunchResult.Invalid(listOf(warning)).toScheduleOutcome().detail)
    }

    /** id 一旦退化成常量，RunLauncher 受理过一轮后同进程里的定时触发全被当重投吞掉 */
    @Test
    fun `request id is stable per trigger and distinct across triggers`() {
        val id = scheduleRequestId("a", 1_000L)

        assertEquals(id, scheduleRequestId("a", 1_000L))
        assertNotEquals(id, scheduleRequestId("a", 2_000L))
        assertNotEquals(id, scheduleRequestId("b", 1_000L))
    }
}
