package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.runner.RunLaunchResult

/** 一次定时发起的记账结果 */
data class ScheduleLaunchOutcome(
    val result: TriggerResult,
    val failureReason: TriggerFailureReason? = null,
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

    RunLaunchResult.ProjectNotReady ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.PROJECT_NOT_READY)

    RunLaunchResult.ConfigurationMissing ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.CONFIGURATION_MISSING)

    RunLaunchResult.NoExecutableTasks ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.NO_EXECUTABLE_TASKS)

    is RunLaunchResult.Invalid ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.INVALID_PLAN)

    is RunLaunchResult.Rejected ->
        ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.REJECTED)

    is RunLaunchResult.Blocked,
    is RunLaunchResult.NeedsConfirmation,
    -> ScheduleLaunchOutcome(TriggerResult.FAILED_START, TriggerFailureReason.BLOCKED)
}
