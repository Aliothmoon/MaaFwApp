package com.aliothmoon.maafw.ui.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScheduleFormattingTest {

    @Test
    fun `低于阈值或原定时刻无效时不展示延迟`() {
        val base = 1_700_000_000_000L
        assertNull(triggerLogDelayUiText(base + 29_000L, base))
        assertNull(triggerLogDelayUiText(base, 0L))
    }

    @Test
    fun `延迟按秒分时分段且不借用间隔文案`() {
        val base = 1_700_000_000_000L
        assertEquals(
            uiTextOf(R.string.schedule_log_delay, uiTextOf(R.string.schedule_log_delay_seconds, 45)),
            triggerLogDelayUiText(base + 45_000L, base),
        )
        assertEquals(
            uiTextOf(R.string.schedule_log_delay, uiTextOf(R.string.schedule_log_delay_minutes, 3)),
            triggerLogDelayUiText(base + 3 * 60_000L, base),
        )
        assertEquals(
            uiTextOf(R.string.schedule_log_delay, uiTextOf(R.string.schedule_log_delay_hours_exact, 2)),
            triggerLogDelayUiText(base + 120 * 60_000L, base),
        )
        assertEquals(
            uiTextOf(R.string.schedule_log_delay, uiTextOf(R.string.schedule_log_delay_hours, 1, 15)),
            triggerLogDelayUiText(base + 75 * 60_000L, base),
        )
    }

    @Test
    fun `日期分组头区分今天昨天当年与跨年`() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals(uiTextOf(R.string.schedule_log_date_today), triggerLogDateHeaderUiText(today, today))
        assertEquals(
            uiTextOf(R.string.schedule_log_date_yesterday),
            triggerLogDateHeaderUiText(today.minusDays(1), today),
        )
        assertEquals(uiTextFormatted("11-18"), triggerLogDateHeaderUiText(LocalDate.of(2026, 11, 18), today))
        assertEquals(uiTextFormatted("2025-12-31"), triggerLogDateHeaderUiText(LocalDate.of(2025, 12, 31), today))
    }
}
