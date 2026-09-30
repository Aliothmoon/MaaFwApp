package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.runner.ExecutionResult
import io.sentry.SpanStatus

/**
 * 整轮结局到 Span 状态的映射，对齐 MXU `finish_run`：取消记 CANCELLED，其余有任务失败即 INTERNAL_ERROR
 *
 * [result] 为 null 是终局到了但结局已被下一轮盖掉，只能按本轮见过的任务成败定
 */
internal fun runSpanStatus(result: ExecutionResult?, hasFailed: Boolean): SpanStatus = when (result) {
    is ExecutionResult.Completed -> SpanStatus.OK
    is ExecutionResult.CompletedWithFailures -> SpanStatus.INTERNAL_ERROR
    is ExecutionResult.Failed -> SpanStatus.INTERNAL_ERROR
    is ExecutionResult.Cancelled -> SpanStatus.CANCELLED
    null -> if (hasFailed) SpanStatus.INTERNAL_ERROR else SpanStatus.OK
}

internal fun taskSpanStatus(success: Boolean): SpanStatus =
    if (success) SpanStatus.OK else SpanStatus.INTERNAL_ERROR

/** Span 上 `result` 的文案，与 MXU `result_label` 一致 */
internal fun resultLabel(status: SpanStatus): String = when (status) {
    SpanStatus.OK -> "success"
    SpanStatus.CANCELLED -> "cancelled"
    else -> "failure"
}
