package com.aliothmoon.maafw.update

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRateMeterTest {

    private val mib = 1024L * 1024

    @Test
    fun `nothing is reported before the minimum span`() {
        val meter = TransferRateMeter()

        assertEquals(TransferRateMeter.UNKNOWN, meter.sample(0, 0))
        assertEquals(TransferRateMeter.UNKNOWN, meter.sample(500, mib))
        assertEquals(2 * mib, meter.sample(1_000, 2 * mib))
    }

    @Test
    fun `the rate follows the recent window`() {
        val meter = TransferRateMeter()
        var bytes = 0L
        for (t in 0L..6_000L step 500) {
            meter.sample(t, bytes)
            bytes += if (t < 3_000) 2 * mib else mib / 2
        }

        assertEquals(mib, meter.sample(6_500, bytes))
    }

    @Test
    fun `a stall drops the rate to zero once it fills the window`() {
        val meter = TransferRateMeter()
        for (t in 0L..2_000L step 500) meter.sample(t, t * 1024)
        val stalled = 2_000L * 1024

        // 窗口从 500ms 那份算起
        assertEquals((stalled - 500L * 1024) * 1000 / 3_000, meter.sample(3_500, stalled))
        for (t in 4_000L..5_000L step 500) meter.sample(t, stalled)
        assertEquals(0L, meter.sample(5_500, stalled))
    }

    @Test
    fun `speed is formatted in binary units`() {
        assertEquals("512 B/s", formatSpeed(512))
        assertEquals("1.5 KB/s", formatSpeed(1_536))
        assertEquals("2.5 MB/s", formatSpeed(5 * mib / 2))
    }
}
