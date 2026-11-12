package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.runner.RunLaunchResult
import com.aliothmoon.maafw.runner.RunRequestId

/** 一次定时发起的记账结果 */
data class ScheduleLaunchOutcome(
    val result: TriggerResult,
    val failureReason: TriggerFailureReason? = null,
    /** 写入触发日志时冻成当时语言；来自 Blocked/Rejected 的 reason 或 Invalid 的首条 Error */
    val detail: UiText? = null,
)

/**
 * 把发起结局翻成落盘记账
 *
 * 抽成纯函数是因为它是这条链路上唯一有判断的一步，而 [ScheduleExecutionService] 是
 * Service，进不了 JVM 单测
 *
 * [RunLaunchResult.NeedsConfirmation] 不该出现：定时触发在 `RunLauncher` 里已经被降级成
 * `Blocked`。真出现了按 BLOCKED 记，不额外开一档——那属于编排层的 bug，日志里查 Timber
 */
fun RunLaunchResult.toScheduleOutcome(): ScheduleLaunchOutcome = when (this) {
    RunLaunchResult.Started ->
        ScheduleLaunchOutcome(TriggerResult.STARTED)

    // 闹钟被重投而已，不是这次触发的结果；调用方应当整条丢掉，不记账
    RunLaunchResult.DuplicateRequest ->
        ScheduleLaunchOutcome(TriggerResult.DUPLICATE)

    RunLaunchResult.ProjectNotReady ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.PROJECT_NOT_READY)

    RunLaunchResult.ConfigurationMissing ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.CONFIGURATION_MISSING)

    RunLaunchResult.NoExecutableTasks ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.NO_EXECUTABLE_TASKS)

    // 只留「配置有错」用户无从下手；带上首条 Error 指到具体 option，其余等改完下一次触发再露面
    is RunLaunchResult.Invalid ->
        ScheduleLaunchOutcome(
            TriggerResult.FAILED_START,
            TriggerFailureReason.INVALID_PLAN,
            diagnostics.firstOrNull { it.severity == DiagnosticSeverity.Error }?.let {
                uiTextOf(R.string.schedule_reason_invalid_plan_detail, it.source, it.message)
            },
        )

    is RunLaunchResult.Rejected ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.REJECTED, reason)

    is RunLaunchResult.Blocked ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.BLOCKED, reason)

    is RunLaunchResult.NeedsConfirmation ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.BLOCKED, prompt)
}

/** 策略 + 原定时刻唯一确定一次触发；系统重投同一个 PendingIntent 时算得出同一个 id */
fun scheduleRequestId(strategyId: String, scheduledTimeMs: Long): RunRequestId =
    RunRequestId("$strategyId@$scheduledTimeMs")

/** 框架侧的落点枚举翻成落盘的那一份；两边分开，schema 不跟着框架改 */
fun com.aliothmoon.maafw.runner.HookOutcome.toTriggerStepOutcome(): TriggerStepOutcome = when (this) {
    com.aliothmoon.maafw.runner.HookOutcome.ENGAGED -> TriggerStepOutcome.ENGAGED
    com.aliothmoon.maafw.runner.HookOutcome.SKIPPED -> TriggerStepOutcome.SKIPPED
    com.aliothmoon.maafw.runner.HookOutcome.FAILED -> TriggerStepOutcome.FAILED
}
