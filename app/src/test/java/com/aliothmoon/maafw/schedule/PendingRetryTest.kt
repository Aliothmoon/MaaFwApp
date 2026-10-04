package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.MAX_RETRY_COUNT
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.RECONNECT_RETRY_COUNT
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.RETRY_DELAY_MS
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.RETRY_RESUME_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRetryTest {

    private val scheduled = 1_000_000_000L

    private fun retry(count: Int = 1, triggerMs: Long = scheduled + RETRY_DELAY_MS) =
        PendingRetry(scheduledTimeMs = scheduled, retryCount = count, triggerMs = triggerMs)

    @Test
    fun `encode and decode round trip`() {
        val original = retry(count = 2)
        assertEquals(original, PendingRetry.decode(original.encode()))
    }

    @Test
    fun `decode rejects missing or malformed records`() {
        assertNull(PendingRetry.decode(null))
        assertNull(PendingRetry.decode(""))
        assertNull(PendingRetry.decode("1,2"))
        assertNull(PendingRetry.decode("a,1,2"))
    }

    /** 进程重启或 TIME_SET 后重排：挂着的重试要原样接回 */
    @Test
    fun `fresh retry survives resync`() {
        assertTrue(retry().isResumable(lastDeliveredMs = null, nowMs = scheduled + 30_000L))
        assertTrue(retry(MAX_RETRY_COUNT).isResumable(lastDeliveredMs = scheduled - 1, nowMs = scheduled))
    }

    /** 时间往回拨了：原定时刻在「现在」之后，照样接回 */
    @Test
    fun `retry survives a clock moved backwards`() {
        assertTrue(retry().isResumable(lastDeliveredMs = null, nowMs = scheduled - 3_600_000L))
    }

    @Test
    fun `retry whose occurrence already ran is dropped`() {
        assertFalse(retry().isResumable(lastDeliveredMs = scheduled, nowMs = scheduled + 30_000L))
    }

    /** 能重排说明规则已读出，慢速接链那一发由正常的下一环接替 */
    @Test
    fun `reconnect slot is not resumed`() {
        assertFalse(retry(RECONNECT_RETRY_COUNT).isResumable(lastDeliveredMs = null, nowMs = scheduled))
        assertFalse(retry(0).isResumable(lastDeliveredMs = null, nowMs = scheduled))
    }

    /** 关机几小时后开机：不补跑旧的那一次 */
    @Test
    fun `stale retry is dropped`() {
        assertTrue(retry().isResumable(null, nowMs = scheduled + RETRY_RESUME_WINDOW_MS))
        assertFalse(retry().isResumable(null, nowMs = scheduled + RETRY_RESUME_WINDOW_MS + 1))
    }

    @Test
    fun `resume keeps the original time when it is still ahead`() {
        val now = scheduled + 10_000L
        assertEquals(scheduled + RETRY_DELAY_MS, retry().resumeAt(now))
    }

    /** 已过点：多半正在投递，隔一个间隔再补，不与那次投递抢槽位 */
    @Test
    fun `resume waits one retry delay when the original time has passed`() {
        val now = scheduled + 10 * RETRY_DELAY_MS
        assertEquals(now + RETRY_DELAY_MS, retry().resumeAt(now))
        val atTrigger = scheduled + RETRY_DELAY_MS
        assertEquals(atTrigger + RETRY_DELAY_MS, retry().resumeAt(atTrigger))
    }

    /** 时钟往回拨后原投递时刻远在未来：不拖过一个重试间隔 */
    @Test
    fun `resume is capped at one retry delay after a clock moved backwards`() {
        val now = scheduled - 3_600_000L
        assertEquals(now + RETRY_DELAY_MS, retry().resumeAt(now))
    }
}
