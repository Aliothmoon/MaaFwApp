package com.aliothmoon.maafw.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleType
import com.aliothmoon.maafw.schedule.TriggerResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 时刻的展示格式
 * 今天之内只给 HH:mm，跨天才带日期——列表里绝大多数条目都在 24 小时内，带上日期反而更难扫
 */
fun formatTriggerTime(epochMs: Long): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val formatter = if (zoned.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) TIME_ONLY else DATE_TIME
    return zoned.format(formatter)
}

/** 星期名取当前 locale 的短名（周一 / Mon），不自己维护译名表 */
fun DayOfWeek.shortLabel(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** 一句话概括规则本身，不含下次触发时刻——那个是算出来的，另起一行 */
@Composable
fun ScheduleStrategy.ruleSummary(): String = when (scheduleType) {
    ScheduleType.FIXED_TIME -> {
        val days = DayOfWeek.entries
            .filter { it in daysOfWeek }
            .joinToString(" ") { it.shortLabel() }
        val times = executionTimes.joinToString(" ") { it.format(TIME_ONLY) }
        listOf(days, times).filter { it.isNotBlank() }.joinToString("  ")
    }

    ScheduleType.INTERVAL -> {
        val start = startTimeMs?.let { formatTriggerTime(it) }.orEmpty()
        val interval = intervalMinutes?.let { formatIntervalMinutes(it) }.orEmpty()
        listOf(start, interval).filter { it.isNotBlank() }.joinToString("  ")
    }
}

/** 60 的整数倍收成小时，否则原样给分钟——「每 90 分钟」比「每 1.5 小时」好读 */
@Composable
fun formatIntervalMinutes(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) {
        stringResource(R.string.schedule_interval_hours, minutes / 60)
    } else {
        stringResource(R.string.schedule_interval_minutes, minutes)
    }

@Composable
fun TriggerResult.label(): String = when (this) {
    TriggerResult.TRIGGERED -> stringResource(R.string.schedule_result_triggered)
    TriggerResult.FAILED_VALIDATION -> stringResource(R.string.schedule_result_failed_validation)
    TriggerResult.FAILED_SERVICE_START -> stringResource(R.string.schedule_result_failed_service)
}

/** 规则完整才会被闹钟接受；UI 用它决定要不要提示「保存后不会触发」 */
val ScheduleStrategy.isComplete: Boolean
    get() = when (scheduleType) {
        ScheduleType.FIXED_TIME -> daysOfWeek.isNotEmpty() && executionTimes.isNotEmpty()
        ScheduleType.INTERVAL -> startTimeMs != null && (intervalMinutes ?: 0) > 0
    }
