package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ProcessExitLogTest {

    private fun record(
        timestamp: Long,
        pid: Int,
        reason: Int = 10,
        status: Int = 0,
        description: String? = null,
    ) = ProcessExitRecord(
        timestamp = timestamp,
        pid = pid,
        processName = "com.example.game",
        reason = reason,
        reasonName = "USER_REQUESTED",
        detail = 0,
        detailName = "",
        status = status,
        importance = 100,
        pssKb = 2048,
        rssKb = 4096,
        description = description,
    )

    @Test
    fun `first run keeps only the newest backfill records`() {
        val records = (1..5).map { record(timestamp = it.toLong(), pid = it) }

        assertEquals(listOf(3, 4, 5), ProcessExitLog.selectNew(records, null).map { it.pid })
    }

    @Test
    fun `watermark advances within the same millisecond by pid`() {
        val records = listOf(
            record(timestamp = 100, pid = 7),
            record(timestamp = 100, pid = 8),
            record(timestamp = 100, pid = 9),
        )

        val fresh = ProcessExitLog.selectNew(records, ProcessExitLog.Watermark(100, 8))

        assertEquals(listOf(9), fresh.map { it.pid })
    }

    @Test
    fun `expected game restarts are informational but kills are warnings`() {
        val restart = record(
            timestamp = 1,
            pid = 1,
            description = "stop com.example.game due to from pid 1234",
        )
        val killed = record(timestamp = 2, pid = 2, reason = 9)

        assertTrue(ProcessExitLog.isExpected(ProcessExitSubject.GAME, restart))
        assertFalse(ProcessExitLog.isExpected(ProcessExitSubject.GAME, killed))
    }

    @Test
    fun `format includes reason memory and description`() {
        val line = ProcessExitLog.format(
            ProcessExitSubject.GAME,
            record(timestamp = 0, pid = 42, description = "low memory"),
            ZoneId.of("UTC"),
        )

        assertTrue(line.contains("Process exit [game] com.example.game pid=42"))
        assertTrue(line.contains("reason=USER_REQUESTED(10)"))
        assertTrue(line.contains("pss=2.0MB"))
        assertTrue(line.contains("rss=4.0MB"))
        assertTrue(line.contains("desc=\"low memory\""))
    }
}
