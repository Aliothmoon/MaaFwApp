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
        task = "DeliveryJobs",
        taskId = 7,
        startedAtMs = 1_000,
        durationMs = 300,
        failedNodes = 2,
        root = FailureSignal("EnterDepot", "recognition", sourceTaskId = 9, nodeId = 3, durationMs = 250),
        terminal = FailureSignal("WulingLoop", "action", sourceTaskId = 7, nodeId = 5, durationMs = null),
        options = mapOf("Wuling" to "true"),
        tags = mapOf("run.id" to "e1", "controller.type" to "Adb"),
        span = span,
    )

    @Test
    fun `标题与分组都落在第一个失败节点上`() {
        val event = failure.toSentryEvent("MaaEnd")

        assertEquals("Maa task failed: DeliveryJobs at EnterDepot (recognition)", event.message?.formatted)
        assertEquals(SentryLevel.ERROR, event.level)
        assertEquals(FAILURE_TRANSACTION, event.transaction)
        assertEquals(
            listOf("maafwapp-task-failure", "MaaEnd", "DeliveryJobs", "EnterDepot", "recognition"),
            event.fingerprints,
        )
    }

    @Test
    fun `可搜的进 tag，明细进 extra`() {
        val event = failure.toSentryEvent("MaaEnd")

        assertEquals(
            mapOf(
                "run.id" to "e1",
                "controller.type" to "Adb",
                "task.name" to "DeliveryJobs",
                "failure.node" to "EnterDepot",
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
                "failure.node" to "EnterDepot",
                "failure.stage" to "recognition",
                "failure.source_task_id" to 9L,
                "failure.node_id" to 3L,
                "failure.duration_ms" to 250L,
                "terminal_failure.node" to "WulingLoop",
                "terminal_failure.stage" to "action",
                "terminal_failure.source_task_id" to 7L,
                "terminal_failure.node_id" to 5L,
                "option.Wuling" to "true",
            ),
            event.extras,
        )
    }

    @Test
    fun `事件挂回任务 Span 所在的 Trace`() {
        val trace = failure.toSentryEvent("MaaEnd").contexts.trace

        assertEquals(spanContext.traceId, trace?.traceId)
        assertEquals(spanContext.spanId, trace?.spanId)
    }

    /** 事务的 Span 数满了之后拿到的是 no-op Span，全零的 trace id 不往事件上写 */
    @Test
    fun `no-op Span 不写 trace 上下文`() {
        val event = failure.copy(span = NoOpSpan.getInstance()).toSentryEvent("MaaEnd")

        assertNull(event.contexts.trace)
    }

    @Test
    fun `没观测到失败节点时用占位`() {
        val event = failure.copy(root = null, terminal = null, taskId = null).toSentryEvent("MaaEnd")

        assertEquals("Maa task failed: DeliveryJobs at terminal_failure (unknown)", event.message?.formatted)
        assertEquals("terminal_failure", event.tags?.get("failure.node"))
        assertNull(event.extras?.get("failure.node"))
        assertNull(event.extras?.get("task.id"))
    }
}
