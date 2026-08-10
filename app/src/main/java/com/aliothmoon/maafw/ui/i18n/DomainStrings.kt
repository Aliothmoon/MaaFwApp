package com.aliothmoon.maafw.ui.i18n

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromProject
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.i18n.uiTextPlural
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunnerPhase

/**
 * 领域值 -> 文案
 *
 * 一律返回 [UiText] 而不是 `@Composable fun …: String`：后者把文案锁死在组合里，
 * 通知栏、日志导出这些同样要展示同一句话的地方就得再写一遍
 */

fun DiagnosticSeverity.asUiText(): UiText = when (this) {
    DiagnosticSeverity.Warning -> uiTextOf(R.string.diagnostic_severity_warning)
    DiagnosticSeverity.Error -> uiTextOf(R.string.diagnostic_severity_error)
}

/** 合成「未分组」组的名字走资源；真实分组 label 是 PI 数据，原样展示 */
fun TaskCatalogGroup.asUiText(): UiText =
    if (isUngrouped) uiTextOf(R.string.tasks_ungrouped) else uiTextFromProject(label)

/** reason 是 Runner 抛回的技术文本，作为参数原样嵌进去，不翻译 */
fun RunnerPhase.asUiText(): UiText = when (this) {
    is RunnerPhase.Unavailable -> uiTextOf(R.string.phase_unavailable, reason)
    RunnerPhase.Idle -> uiTextOf(R.string.phase_idle)
    RunnerPhase.Preparing -> uiTextOf(R.string.phase_preparing)
    RunnerPhase.Running -> uiTextOf(R.string.phase_running)
    RunnerPhase.Stopping -> uiTextOf(R.string.phase_stopping)
}

fun ExecutionResult.asUiText(): UiText = when (this) {
    is ExecutionResult.Completed ->
        uiTextPlural(R.plurals.result_completed, taskResults.size, taskResults.size)

    is ExecutionResult.CompletedWithFailures ->
        taskResults.count { !it.success }.let { failures ->
            uiTextPlural(R.plurals.result_completed_with_failures, failures, failures)
        }

    is ExecutionResult.Cancelled -> uiTextOf(R.string.result_cancelled)
    is ExecutionResult.Failed -> uiTextOf(R.string.result_failed, reason)
}

/** 有错误时才切到带错误数的那条；两条的选形数不同，不能合并 */
fun diagnosticsSummaryUiText(total: Int, errors: Int): UiText =
    if (errors > 0) {
        uiTextPlural(R.plurals.home_diagnostics_summary_with_errors, errors, total, errors)
    } else {
        uiTextOf(R.string.home_diagnostics_summary, total)
    }
