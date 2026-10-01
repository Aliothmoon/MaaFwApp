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
 * `app.*` 与 `maafwapp.*` 的 tag 由全局 scope 带上，这里不重复写；
 * 截图与日志正文不带，MXU 那套 `attachment.*` / `logs.*` 也就没有
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
