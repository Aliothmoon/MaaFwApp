package com.aliothmoon.maafw.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 由规则推下一个触发时刻；纯函数，不碰 Context 也不读系统时钟
 *
 * [now] 与 [afterEpochMs] 分开：前者是「现在几点」，后者是「上一次算到的那个点」——
 * 触发后续闹钟时必须以上一个计划时刻为界往后找，否则会把刚触发的那个点又算一遍
 *
 * 返回 null = 规则不完整（没选星期 / 没填时刻 / 间隔非正），调用方据此判定「不会触发」
 */
fun nextTriggerOf(
    strategy: ScheduleStrategy,
    now: ZonedDateTime,
    afterEpochMs: Long = 0L,
): ZonedDateTime? = when (strategy.scheduleType) {
    ScheduleType.FIXED_TIME -> nextFixedTime(strategy, now, afterEpochMs)
    ScheduleType.INTERVAL -> nextInterval(strategy, now, afterEpochMs)
}

/** 从基准日往后扫 8 天：跨过整周还找不到，说明星期集合与时刻集合有一个是空的 */
private fun nextFixedTime(
    strategy: ScheduleStrategy,
    now: ZonedDateTime,
    afterEpochMs: Long,
): ZonedDateTime? {
    if (strategy.daysOfWeek.isEmpty() || strategy.executionTimes.isEmpty()) return null
    val zone = now.zone
    val baseline = if (afterEpochMs > 0L) {
        maxOf(now, Instant.ofEpochMilli(afterEpochMs).atZone(zone))
    } else {
        now
    }
    for (dayOffset in 0..7) {
        val date = baseline.toLocalDate().plusDays(dayOffset.toLong())
        if (date.dayOfWeek !in strategy.daysOfWeek) continue
        strategy.executionTimes.forEach { time ->
            val candidate = ZonedDateTime.of(date, time, zone)
            if (candidate.isAfter(baseline)) return candidate
        }
    }
    return null
}

private fun nextInterval(
    strategy: ScheduleStrategy,
    now: ZonedDateTime,
    afterEpochMs: Long,
): ZonedDateTime? {
    val startMs = strategy.startTimeMs ?: return null
    val intervalMs = (strategy.intervalMinutes ?: return null) * 60_000L
    if (intervalMs <= 0L) return null
    val baseline = maxOf(now.toInstant().toEpochMilli(), afterEpochMs)
    val nextMs = if (startMs > baseline) {
        startMs
    } else {
        // 直接跳到基准之后的第一个整数倍，不逐个累加：关机几天后开机不该空转几千次循环
        startMs + ((baseline - startMs) / intervalMs + 1) * intervalMs
    }
    return Instant.ofEpochMilli(nextMs).atZone(now.zone)
}

/** 系统默认时区的当下；生产调用点统一走它，测试传自己造的时刻 */
fun systemNow(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
