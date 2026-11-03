package com.aliothmoon.maafw.telemetry

import io.sentry.SentryLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDeathReportTest {

    private val pending = ServiceDeathForensics.Pending(
        eventId = "0123456789abcdef0123456789abcdef",
        runId = "run-1",
        backend = "root",
        diedAtMs = 1_000_000L,
        pid = 123,
    )

    @Test
    fun `long system logs keep chunk attributes and join back to the source`() {
        val content = "[ERR] lowmemorykiller killed service\n" + "x".repeat(14_000)

        val records = serviceDeathLogRecords(
            content = content,
            pending = pending,
            connectedPid = 456,
            delayMs = 42_000L,
        )

        assertTrue(records.size > 1)
        assertEquals(content, records.joinToString("") { it.body })
        assertEquals(records.indices.toList(), records.map { it.attributes["log.chunk_index"] })
        assertTrue(records.all { it.attributes["log.chunk_count"] == records.size })
        assertTrue(records.all { it.body.toByteArray().size <= 6 * 1024 })
        assertEquals(SentryLogLevel.ERROR, records.first().level)
    }

    @Test
    fun `records carry the original event and both service pids`() {
        val record = serviceDeathLogRecords(
            content = "# window 1000..2000\n",
            pending = pending,
            connectedPid = 456,
            delayMs = 42_900L,
        ).single()

        assertEquals("0123456789abcdef0123456789abcdef", record.attributes["sentry.event_id"])
        assertEquals("service_death", record.attributes["diagnostic.reason"])
        assertEquals("logcat/system", record.attributes["diagnostic.source"])
        assertEquals("run-1", record.attributes["run.id"])
        assertEquals("root", record.attributes["service.backend"])
        assertEquals(123, record.attributes["service.pid"])
        assertEquals(456, record.attributes["service.connected_pid"])
        assertEquals("42", record.attributes["forensics.delay_s"])
    }
}
