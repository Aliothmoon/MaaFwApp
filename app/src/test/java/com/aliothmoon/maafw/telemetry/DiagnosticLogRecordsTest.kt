package com.aliothmoon.maafw.telemetry

import io.sentry.SentryLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogRecordsTest {

    private val context = DiagnosticLogContext(
        eventId = "0123456789abcdef0123456789abcdef",
        runId = "e1",
        taskId = 7,
        task = "Reward",
        failureNode = "FindReward",
        failureStage = "recognition",
        traceId = "fedcba9876543210fedcba9876543210",
        spanId = "0123456789abcdef",
    )

    private fun log(content: String) = DiagnosticLog("maafw.log", "maafw", content, content.length.toLong())

    @Test
    fun `每条记录带着找回事件与拼回原文所需的属性`() {
        val record = log("[INF] started\n").toRecords(context).single()

        assertEquals("[INF] started\n", record.body)
        assertEquals(SentryLogLevel.INFO, record.level)
        assertEquals(
            mapOf(
                "diagnostic.reason" to "task_failure",
                "diagnostic.source" to "maafw.log",
                "diagnostic.kind" to "maafw",
                "sentry.event_id" to context.eventId,
                "run.id" to "e1",
                "task.name" to "Reward",
                "failure.node" to "FindReward",
                "failure.stage" to "recognition",
                "task.id" to 7L,
                "log.raw_bytes" to 14L,
                "trace.id" to context.traceId,
                "span.id" to context.spanId,
                "log.chunk_index" to 0,
                "log.chunk_count" to 1,
            ),
            record.attributes,
        )
    }

    /** 一批 100 条不能超过 Relay 的 1 MiB，单条连属性一起压在 7 KiB 以内 */
    @Test
    fun `长日志切成有上限的若干条，拼起来还是原文`() {
        val content = "[ERR] 识别失败 \"node\"\n".repeat(2_000)
        val records = log(content).toRecords(context)

        assertTrue(records.size > 1)
        assertEquals(content, records.joinToString("") { it.body })
        assertEquals(records.indices.toList(), records.map { it.attributes["log.chunk_index"] })
        assertTrue(records.all { it.attributes["log.chunk_count"] == records.size })
        assertTrue(records.all { it.body.toByteArray().size <= 7 * 1024 })
        assertEquals(SentryLogLevel.ERROR, records.first().level)
    }

    @Test
    fun `按转义后的字节数切，不在字符中间下刀`() {
        // 引号转义后占 2 字节、汉字 3 字节、emoji 是一对代理项占 4 字节
        assertEquals(listOf("\"\"", "\"\""), chunkLogBody("\"\"\"\"", budget = 4))
        assertEquals(listOf("中", "文"), chunkLogBody("中文", budget = 5))
        assertEquals(listOf("😀", "😀"), chunkLogBody("😀😀", budget = 6))
        assertTrue(chunkLogBody("", budget = 4).isEmpty())
    }

    @Test
    fun `过长或带控制字符的属性值截断并留标记`() {
        val long = DiagnosticLogContext(
            eventId = context.eventId,
            runId = "e1",
            taskId = null,
            task = "a".repeat(300),
            failureNode = "line\nbreak",
            failureStage = "action",
            traceId = null,
            spanId = null,
        )
        val attributes = log("body").toRecords(long).single().attributes

        assertEquals(200, (attributes["task.name"] as String).length)
        assertEquals("line�break", attributes["failure.node"])
        assertEquals(true, attributes["diagnostic.attributes_truncated"])
        assertFalse("task.id" in attributes)
        assertFalse("trace.id" in attributes)
    }
}
