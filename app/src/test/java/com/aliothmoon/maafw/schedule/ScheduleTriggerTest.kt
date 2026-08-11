package com.aliothmoon.maafw.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTriggerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-08-10 是周一 */
    private fun at(text: String): ZonedDateTime =
        LocalDateTime.parse(text).atZone(zone)

    private fun fixed(
        days: Set<DayOfWeek>,
        vararg times: String,
    ) = ScheduleStrategy(
        name = "t",
        scheduleType = ScheduleType.FIXED_TIME,
        daysOfWeek = days,
        executionTimes = times.map(LocalTime::parse).sorted(),
    )

    @Test
    fun `fixed time picks the next slot later today`() {
        val next = nextTriggerOf(
            fixed(setOf(DayOfWeek.MONDAY), "08:00", "20:00"),
            now = at("2026-08-10T09:00"),
        )
        assertEquals(at("2026-08-10T20:00"), next)
    }

    @Test
    fun `fixed time rolls to the next enabled weekday`() {
        val next = nextTriggerOf(
            fixed(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), "08:00"),
            now = at("2026-08-10T09:00"),
        )
        assertEquals(at("2026-08-13T08:00"), next)
    }

    @Test
    fun `fixed time wraps around the week when only one day is enabled`() {
        val next = nextTriggerOf(
            fixed(setOf(DayOfWeek.MONDAY), "08:00"),
            now = at("2026-08-10T09:00"),
        )
        assertEquals(at("2026-08-17T08:00"), next)
    }

    /** 触发后续排时以上一个计划时刻为界，否则会把刚响过的那个点再算一遍 */
    @Test
    fun `fixed time skips the slot that just fired`() {
        val strategy = fixed(setOf(DayOfWeek.MONDAY), "08:00", "20:00")
        val justFired = at("2026-08-10T08:00").toInstant().toEpochMilli()
        val next = nextTriggerOf(strategy, now = at("2026-08-10T08:00"), afterEpochMs = justFired)
        assertEquals(at("2026-08-10T20:00"), next)
    }

    @Test
    fun `fixed time without days or times never fires`() {
        assertNull(nextTriggerOf(fixed(emptySet(), "08:00"), now = at("2026-08-10T09:00")))
        assertNull(nextTriggerOf(fixed(setOf(DayOfWeek.MONDAY)), now = at("2026-08-10T09:00")))
    }

    @Test
    fun `interval before the start time fires at the start time`() {
        val start = at("2026-08-10T12:00").toInstant().toEpochMilli()
        val strategy = ScheduleStrategy(
            name = "t",
            scheduleType = ScheduleType.INTERVAL,
            startTimeMs = start,
            intervalHours = 2,
        )
        assertEquals(at("2026-08-10T12:00"), nextTriggerOf(strategy, now = at("2026-08-10T09:00")))
    }

    /** 关机数天后开机：应一步跳到基准之后的第一个整数倍，而不是逐个累加 */
    @Test
    fun `interval jumps straight past a long outage`() {
        val start = at("2026-08-01T00:00").toInstant().toEpochMilli()
        val strategy = ScheduleStrategy(
            name = "t",
            scheduleType = ScheduleType.INTERVAL,
            startTimeMs = start,
            intervalHours = 6,
        )
        assertEquals(at("2026-08-10T12:00"), nextTriggerOf(strategy, now = at("2026-08-10T09:30")))
    }

    @Test
    fun `interval without a usable period never fires`() {
        val base = ScheduleStrategy(name = "t", scheduleType = ScheduleType.INTERVAL)
        assertNull(nextTriggerOf(base, now = at("2026-08-10T09:00")))
        assertNull(
            nextTriggerOf(
                base.copy(startTimeMs = 0L, intervalHours = 0),
                now = at("2026-08-10T09:00"),
            ),
        )
    }
}
