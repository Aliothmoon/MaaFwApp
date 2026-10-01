package com.aliothmoon.maafw.telemetry

import io.mockk.every
import io.mockk.mockk
import io.sentry.ISpan
import io.sentry.NoOpSpan
import io.sentry.SentryLevel
import io.sentry.SpanContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskFailureTest {

    private val spanContext = SpanContext(RunTracer.TASK_OP)
    private val span = mockk<ISpan> { every { spanContext } returns this@TaskFailureTest.spanContext }

    private val failure = TaskFailure(
        runId = "e1",
        task = "Reward",
        taskId = 7,
        startedAtMs = 1_000,
        durationMs = 300,
        failedNodes = 2,
        root = FailureSignal("FindReward", "recognition", sourceTaskId = 9, nodeId = 3, durationMs = 250),
        terminal = FailureSignal("RewardLoop", "action", sourceTaskId = 7, nodeId = 5, durationMs = null),
        options = mapOf("server" to "cn"),
        tags = mapOf("run.id" to "e1", "controller.type" to "Adb"),
        span = span,
    )

    @Test
    fun `标题与分组都落在第一个失败节点上`() {
        val event = failure.toSentryEvent("Demo")

        assertEquals("Maa task failed: Reward at FindReward (recognition)", event.message?.formatted)
        assertEquals(SentryLevel.ERROR, event.level)
        assertEquals(FAILURE_TRANSACTION, event.transaction)
        assertEquals(
            listOf("maafwapp-task-failure", "Demo", "Reward", "FindReward", "recognition"),
            event.fingerprints,
        )
    }

    @Test
    fun `可搜的进 tag，明细进 extra`() {
        val event = failure.toSentryEvent("Demo")

        assertEquals(
            mapOf(
                "run.id" to "e1",
                "controller.type" to "Adb",
                "task.name" to "Reward",
                "failure.node" to "FindReward",
                "failure.stage" to "recognition",
                "result" to "failure",
            ),
            event.tags,
        )
        assertEquals(
            mapOf(
                "run.id" to "e1",
                "task.id" to 7L,
                "failure.count" to 2,
                "task.duration_ms" to 300L,
                "task.started_at_ms" to 1_000L,
                "failure.node" to "FindReward",
                "failure.stage" to "recognition",
                "failure.source_task_id" to 9L,
                "failure.node_id" to 3L,
                "failure.duration_ms" to 250L,
                "terminal_failure.node" to "RewardLoop",
                "terminal_failure.stage" to "action",
                "terminal_failure.source_task_id" to 7L,
                "terminal_failure.node_id" to 5L,
                "option.server" to "cn",
            ),
            event.extras,
        )
    }

    @Test
    fun `事件挂回任务 Span 所在的 Trace`() {
        val trace = failure.toSentryEvent("Demo").contexts.trace

        assertEquals(spanContext.traceId, trace?.traceId)
        assertEquals(spanContext.spanId, trace?.spanId)
    }

    /** 事务的 Span 数满了之后拿到的是 no-op Span，全零的 trace id 不往事件上写 */
    @Test
    fun `no-op Span 不写 trace 上下文`() {
        val event = failure.copy(span = NoOpSpan.getInstance()).toSentryEvent("Demo")

        assertNull(event.contexts.trace)
    }

    @Test
    fun `没观测到失败节点时用占位`() {
        val event = failure.copy(root = null, terminal = null, taskId = null).toSentryEvent("Demo")

        assertEquals("Maa task failed: Reward at terminal_failure (unknown)", event.message?.formatted)
        assertEquals("terminal_failure", event.tags?.get("failure.node"))
        assertNull(event.extras?.get("failure.node"))
        assertNull(event.extras?.get("task.id"))
    }

    private fun evidenceExtras(logs: DiagnosticLogs?, attachment: AttachmentOutcome): Map<String, Any?> =
        failure.toSentryEvent("Demo").apply { setEvidence(logs, attachment) }.extras.orEmpty()
            .filterKeys { it.startsWith("logs.") || it.startsWith("attachment.") }

    @Test
    fun `证据带上了就记下各带了多少`() {
        val logs = DiagnosticLogs(
            entries = listOf(DiagnosticLog("maafw.log", "maafw", "failed", 6)),
            selectedRawBytes = 6,
            truncated = true,
            warnings = listOf("new_log_included_whole:maafw.log", "open_image_failed:on_error/a.png"),
        )
        val attachment = AttachmentOutcome.Attached(ByteArray(40), "bundle.zip", imageCount = 2, selectedRawBytes = 30)

        assertEquals(
            mapOf(
                "logs.status" to "captured",
                "logs.count" to 1,
                "logs.selected_raw_bytes" to 6L,
                "logs.truncated" to true,
                "logs.warnings" to "new_log_included_whole:maafw.log,open_image_failed:on_error/a.png",
                "attachment.status" to "attached",
                "attachment.image_count" to 2,
                "attachment.selected_raw_bytes" to 30L,
                "attachment.bundle_bytes" to 40,
                "attachment.selection" to "new_on_error_screenshots",
            ),
            evidenceExtras(logs, attachment),
        )
    }

    @Test
    fun `证据没带上就记下原因`() {
        assertEquals(
            mapOf("logs.status" to "not_available", "attachment.status" to "not_selected"),
            evidenceExtras(null, AttachmentOutcome.NotSelected),
        )
        assertEquals(
            mapOf(
                "logs.status" to "no_evidence",
                "logs.count" to 0,
                "logs.selected_raw_bytes" to 0L,
                "logs.truncated" to false,
                "attachment.status" to "raw_too_large",
                "attachment.detail" to "too large",
                "attachment.selected_raw_bytes" to 9L,
            ),
            evidenceExtras(
                DiagnosticLogs(emptyList(), 0, truncated = false, warnings = emptyList()),
                AttachmentOutcome.Omitted("raw_too_large", "too large", selectedRawBytes = 9),
            ),
        )
    }

    @Test
    fun `日志记录的关联信息取自事件自己`() {
        val event = failure.toSentryEvent("Demo")
        val context = event.diagnosticLogContext(failure)

        assertEquals(event.eventId.toString(), context.eventId)
        assertEquals("FindReward", context.failureNode)
        assertEquals(spanContext.traceId.toString(), context.traceId)
        assertEquals(spanContext.spanId.toString(), context.spanId)
    }
}
