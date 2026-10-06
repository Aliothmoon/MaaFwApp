package com.aliothmoon.maafw.telemetry

import io.sentry.ISpan
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId

/** 一个直接观测到的失败节点 */
internal data class FailureSignal(
    val node: String,
    /** `action`：命中节点后动作没成；`recognition`：`next` 在 reco_timeout 内始终没命中 */
    val stage: String,
    /** 嵌套 `Context.run_task` 时是子 pipeline 的 task id，与外层任务的不同 */
    val sourceTaskId: Long,
    val nodeId: Long?,
    val durationMs: Long?,
)

/** 一个外层任务的终态失败，由 [RunTracer] 攒出、[toSentryEvent] 变成一条可聚类的 Error Event */
internal data class TaskFailure(
    val runId: String,
    val task: String,
    /** MaaFW 的 task id，`Tasker.Task.Starting` 没到就失败的任务没有 */
    val taskId: Long?,
    val startedAtMs: Long,
    val durationMs: Long,
    val failedNodes: Int,
    /** 第一个失败节点，按它分组；任务没报过失败节点就终态失败时为 null */
    val root: FailureSignal?,
    /** 最后一个失败节点，与 [root] 相同时为 null */
    val terminal: FailureSignal?,
    val options: Map<String, String>,
    val tags: Map<String, String>,
    /** 任务 Span，事件靠它的上下文挂回所在的 Trace */
    val span: ISpan,
)

/**
 * 字段逐项对齐 MXU `capture_failure_event`，名字前缀换成外壳自己的：标题同形，
 * 两个客户端的同一个失败节点在 Issues 里各成一组、靠标题就能对上
 *
 * `app.*` 与 `maafwapp.*` 的 tag 由全局 scope 带上，这里不重复写；证据的去向另由 [setEvidence] 记
 */
internal fun TaskFailure.toSentryEvent(appName: String): SentryEvent {
    val node = root?.node ?: UNOBSERVED_NODE
    val stage = root?.stage ?: UNOBSERVED_STAGE

    return SentryEvent().also { event ->
        event.level = SentryLevel.ERROR
        event.logger = FAILURE_LOGGER
        event.transaction = FAILURE_TRANSACTION
        event.message = Message().apply { formatted = "Maa task failed: $task at $node ($stage)" }
        event.fingerprints = listOf(FAILURE_FINGERPRINT, appName, task, node, stage)

        (tags + mapOf("task.name" to task, "failure.node" to node, "failure.stage" to stage, "result" to "failure"))
            .forEach { (key, value) -> event.setTag(key, value.take(MAX_TAG_VALUE_LENGTH)) }

        event.setExtra("run.id", runId)
        taskId?.let { event.setExtra("task.id", it) }
        event.setExtra("failure.count", failedNodes)
        event.setExtra("task.duration_ms", durationMs)
        event.setExtra("task.started_at_ms", startedAtMs)
        root?.let { event.setSignal("failure", it) }
        terminal?.let { event.setSignal("terminal_failure", it) }
        options.forEach { (key, value) -> event.setExtra("option.$key", value) }

        // 事务的 Span 数满了之后任务 Span 是 no-op，它的 trace id 是全零，写上去反而指错地方
        span.spanContext.takeIf { it.traceId != SentryId.EMPTY_ID }?.let(event.contexts::setTrace)
    }
}

/**
 * 证据带没带上、为什么没带，写进 `logs.*` 与 `attachment.*`，键与取值同 MXU：
 * 日志正文走 Sentry Logs、截图是附件，事件自己身上只留这份摘要
 */
internal fun SentryEvent.setEvidence(logs: DiagnosticLogs?, attachment: AttachmentOutcome) {
    if (logs == null) {
        setExtra("logs.status", "not_available")
    } else {
        setExtra("logs.status", if (logs.entries.isEmpty()) "no_evidence" else "captured")
        setExtra("logs.count", logs.entries.size)
        setExtra("logs.selected_raw_bytes", logs.selectedRawBytes)
        setExtra("logs.truncated", logs.truncated)
        if (logs.warnings.isNotEmpty()) setExtra("logs.warnings", logs.warnings.joinToString(","))
    }
    when (attachment) {
        AttachmentOutcome.NotSelected -> setExtra("attachment.status", "not_selected")
        is AttachmentOutcome.Attached -> {
            setExtra("attachment.status", "attached")
            setExtra("attachment.image_count", attachment.imageCount)
            setExtra("attachment.selected_raw_bytes", attachment.selectedRawBytes)
            setExtra("attachment.bundle_bytes", attachment.bytes.size)
            setExtra("attachment.selection", "new_on_error_screenshots")
        }
        is AttachmentOutcome.Omitted -> {
            setExtra("attachment.status", attachment.status)
            setExtra("attachment.detail", attachment.detail)
            attachment.selectedRawBytes?.let { setExtra("attachment.selected_raw_bytes", it) }
            attachment.bundleBytes?.let { setExtra("attachment.bundle_bytes", it) }
        }
    }
}

/** 这条事件名下的日志记录要带的关联信息；事件 ID 在构造时就定了，发之前即可引用 */
internal fun SentryEvent.diagnosticLogContext(failure: TaskFailure): DiagnosticLogContext {
    val trace = contexts.trace
    return DiagnosticLogContext(
        eventId = eventId.toString(),
        runId = failure.runId,
        taskId = failure.taskId,
        task = failure.task,
        failureNode = failure.root?.node ?: UNOBSERVED_NODE,
        failureStage = failure.root?.stage ?: UNOBSERVED_STAGE,
        traceId = trace?.traceId?.toString(),
        spanId = trace?.spanId?.toString(),
    )
}

private fun SentryEvent.setSignal(prefix: String, signal: FailureSignal) {
    setExtra("$prefix.node", signal.node)
    setExtra("$prefix.stage", signal.stage)
    setExtra("$prefix.source_task_id", signal.sourceTaskId)
    signal.nodeId?.let { setExtra("$prefix.node_id", it) }
    signal.durationMs?.let { setExtra("$prefix.duration_ms", it) }
}

internal const val FAILURE_TRANSACTION = "maafwapp.task.failure"
private const val FAILURE_LOGGER = "maafwapp.task"
private const val FAILURE_FINGERPRINT = "maafwapp-task-failure"

/** 任务没报过失败节点就终态失败时的占位，与 MXU 一致 */
private const val UNOBSERVED_NODE = "terminal_failure"
private const val UNOBSERVED_STAGE = "unknown"

/** Sentry 对 tag 值的长度上限 */
private const val MAX_TAG_VALUE_LENGTH = 200
