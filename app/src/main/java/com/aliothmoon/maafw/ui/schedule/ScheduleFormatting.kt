package com.aliothmoon.maafw.ui.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextJoin
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleType
import com.aliothmoon.maafw.schedule.TriggerFailureReason
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

/** 规则各段之间的间隔，比单个空格宽才分得开 */
private const val SEGMENT_SEPARATOR = "  "

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
fun ScheduleStrategy.asUiText(): UiText = when (scheduleType) {
    ScheduleType.FIXED_TIME -> uiTextJoin(
        // java.time 的产物已经随 locale 变，不查资源
        uiTextFormatted(DayOfWeek.entries.filter { it in daysOfWeek }.joinToString(" ") { it.shortLabel() }),
        uiTextFormatted(executionTimes.joinToString(" ") { it.format(TIME_ONLY) }),
        separator = SEGMENT_SEPARATOR,
    )

    ScheduleType.INTERVAL -> uiTextJoin(
        uiTextFormatted(startTimeMs?.let { formatTriggerTime(it) }),
        intervalUiText(intervalDays ?: 0, intervalHours ?: 0),
        separator = SEGMENT_SEPARATOR,
    )
}

/** 间隔展示：天/小时按需拼，都为 0 返回空 */
fun intervalUiText(days: Int, hours: Int): UiText = when {
    days > 0 && hours > 0 -> uiTextJoin(
        uiTextOf(R.string.schedule_interval_days, days),
        uiTextOf(R.string.schedule_interval_hours, hours),
        separator = " ",
    )
    days > 0 -> uiTextOf(R.string.schedule_interval_days, days)
    hours > 0 -> uiTextOf(R.string.schedule_interval_hours, hours)
    else -> UiText.Empty
}

fun TriggerResult.asUiText(): UiText = when (this) {
    TriggerResult.TRIGGERED -> uiTextOf(R.string.schedule_result_triggered)
    TriggerResult.STARTED -> uiTextOf(R.string.schedule_result_started)
    TriggerResult.DUPLICATE -> uiTextOf(R.string.schedule_result_duplicate)
    TriggerResult.FAILED_START -> uiTextOf(R.string.schedule_result_failed_start)
    TriggerResult.FAILED_VALIDATION -> uiTextOf(R.string.schedule_result_failed_validation)
    TriggerResult.FAILED_SERVICE_START -> uiTextOf(R.string.schedule_result_failed_service)
}

/** 没发起成功时补一句为什么；[TriggerFailureReason] 是稳定枚举，文案在这里现场解析 */
fun TriggerFailureReason.asUiText(): UiText = when (this) {
    TriggerFailureReason.PROJECT_NOT_READY -> uiTextOf(R.string.msg_project_not_loaded)
    TriggerFailureReason.CONFIGURATION_MISSING -> uiTextOf(R.string.schedule_reason_configuration_missing)
    TriggerFailureReason.NO_EXECUTABLE_TASKS -> uiTextOf(R.string.msg_no_executable_tasks)
    TriggerFailureReason.INVALID_PLAN -> uiTextOf(R.string.schedule_reason_invalid_plan)
    TriggerFailureReason.BLOCKED -> uiTextOf(R.string.schedule_reason_blocked)
    TriggerFailureReason.REJECTED -> uiTextOf(R.string.schedule_reason_rejected)
}

/**
 * 一次触发的时刻行：实际时刻，延迟超过阈值才补上延迟量
 * 延迟就是 Doze 与厂商省电策略造成的，几秒的抖动没有展示价值
 */
fun triggerLogTimeUiText(actualAt: Long, scheduledAt: Long): UiText {
    val actual = uiTextFormatted(formatTriggerTime(actualAt))
    val delaySeconds = ((actualAt - scheduledAt) / 1000L).coerceAtLeast(0L)
    if (scheduledAt <= 0L || delaySeconds < DELAY_THRESHOLD_SECONDS) return actual
    val delay = if (delaySeconds >= 60L) {
        uiTextOf(R.string.schedule_interval_minutes, (delaySeconds / 60L).toInt())
    } else {
        uiTextFormatted("${delaySeconds}s")
    }
    return uiTextJoin(actual, uiTextOf(R.string.schedule_log_delay, delay), separator = " · ")
}

/** 规则完整才会被闹钟接受；UI 用它决定要不要提示「保存后不会触发」 */
val ScheduleStrategy.isComplete: Boolean
    get() = when (scheduleType) {
        ScheduleType.FIXED_TIME -> daysOfWeek.isNotEmpty() && executionTimes.isNotEmpty()
        ScheduleType.INTERVAL -> startTimeMs != null && (intervalDays ?: 0) * 24 + (intervalHours ?: 0) > 0
    }

private const val DELAY_THRESHOLD_SECONDS = 30L
