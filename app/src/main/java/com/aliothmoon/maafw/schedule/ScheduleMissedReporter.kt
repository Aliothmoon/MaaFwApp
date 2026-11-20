package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.notification.ScheduleNotice
import com.aliothmoon.maafw.ui.schedule.asUiText
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * 重排时判出来的漏触发：补一条触发日志，记到规则上
 *
 * 到点根本没被唤起（进程被杀清了闹钟、到点时关着机）的那一次什么痕迹都不留，
 * 用户查「昨晚为什么没跑」只看得到一片空白
 */
class ScheduleMissedReporter(
    private val alarms: ScheduleAlarmManager,
    private val store: ScheduleStrategyStore,
    private val triggerLog: ScheduleTriggerLog,
    private val notifications: NotificationCenter,
) {

    /** 规则读盘之后调：漏掉的是哪条规则得在规则表里查得到，查不到的（已删）丢掉 */
    suspend fun reportMissed() {
        store.isLoaded.first { it }
        val reported = alarms.drainMissed().mapNotNull { missed ->
            val strategy = store.findById(missed.strategyId) ?: return@mapNotNull null
            val reason = if (missed.rebooted) TriggerFailureReason.MISSED_REBOOTED else TriggerFailureReason.MISSED
            Timber.w("Schedule %s missed at %d (%s)", strategy.id, missed.scheduledTimeMs, reason)
            triggerLog.append(
                TriggerLogEntry(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    scheduledAt = missed.scheduledTimeMs,
                    // 没有被叫醒的那一刻；记成原定时刻，时刻行不会多出一段「延迟」
                    actualAt = missed.scheduledTimeMs,
                    result = TriggerResult.FAILED_START,
                    failureReason = reason,
                ),
            )
            store.recordTrigger(strategy.id, TriggerResult.FAILED_START, triggeredAt = missed.scheduledTimeMs)
            strategy.name to reason
        }
        notifyMissed(reported)
    }

    /** 关机一夜可能漏好几次：合成一条，按原定时刻排好的最后一条就是最近那次 */
    private suspend fun notifyMissed(reported: List<Pair<String, TriggerFailureReason>>) {
        val (latestName, latestReason) = reported.lastOrNull() ?: return
        if (reported.size == 1) {
            notifications.onScheduleNotStarted(latestName, latestReason.asUiText(), ScheduleNotice.FAILED)
            return
        }
        notifications.onSchedulesNotStarted(
            reported.map { it.first }.distinct(),
            uiTextOf(R.string.schedule_missed_many, reported.size, latestReason.asUiText()),
            ScheduleNotice.FAILED,
        )
    }
}
