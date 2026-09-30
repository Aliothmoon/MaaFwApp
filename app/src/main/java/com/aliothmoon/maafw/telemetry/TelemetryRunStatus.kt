package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.runner.ExecutionResult
import io.sentry.SpanStatus

/**
 * 整轮结局到 Span 状态的映射，对齐 MXU `finish_run`：有任务失败即 INTERNAL_ERROR；
 * 拿不到结局（被对账收回等）按取消算，不冒充成功
 */
internal fun runSpanStatus(result: ExecutionResult?): SpanStatus = when (result) {
    is ExecutionResult.Completed -> SpanStatus.OK
    is ExecutionResult.CompletedWithFailures -> SpanStatus.INTERNAL_ERROR
    is ExecutionResult.Failed -> SpanStatus.INTERNAL_ERROR
    is ExecutionResult.Cancelled -> SpanStatus.CANCELLED
    null -> SpanStatus.CANCELLED
}

internal fun taskSpanStatus(success: Boolean): SpanStatus =
    if (success) SpanStatus.OK else SpanStatus.INTERNAL_ERROR

/** Span 上 `result` 的文案，与 MXU `result_label` 一致 */
internal fun resultLabel(status: SpanStatus): String = when (status) {
    SpanStatus.OK -> "success"
    SpanStatus.CANCELLED -> "cancelled"
    else -> "failure"
}
