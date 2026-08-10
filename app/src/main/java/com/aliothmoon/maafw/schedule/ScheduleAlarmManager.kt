package com.aliothmoon.maafw.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aliothmoon.maafw.MainActivity
import timber.log.Timber
import java.time.ZonedDateTime

/**
 * 把 [ScheduleStrategy] 翻成系统闹钟
 *
 * 一条策略同一时刻只挂一个闹钟，requestCode 由 id 推导，重复注册即覆盖
 * 闹钟不自续：每次触发后由 [ScheduleExecutionService] 调 [scheduleNext] 接上下一环，
 * 服务起不来时由 [ScheduleReceiver] 兜底补注册——否则链一断就再也不响
 */
class ScheduleAlarmManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleNext(strategy: ScheduleStrategy, afterEpochMs: Long = 0L) {
        if (!strategy.enabled) return
        val next = computeNextTrigger(strategy, afterEpochMs)
        if (next == null) {
            Timber.d("策略 %s 算不出下一个触发点，不注册", strategy.id)
            return
        }
        val triggerMs = next.toInstant().toEpochMilli()
        val pendingIntent = buildTriggerIntent(strategy.id, triggerMs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // 没有精确闹钟权限时不能退化成 setAndAllowWhileIdle：inexact 闹钟发出的广播在 12+
            // 不在前台服务后台启动的豁免清单里，服务起不来，定时会永久空转
            // setAlarmClock 不要 SCHEDULE_EXACT_ALARM、强制脱 Doze 投递，且同属 exact 而享有豁免，
            // 代价只是状态栏多一个闹钟图标
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerMs, buildShowIntent()),
                pendingIntent,
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }
        Timber.i("策略 %s 下次触发 %s", strategy.id, next)
    }

    /** API 31 起用户可单独关掉精确闹钟；关了仍能定时（走 setAlarmClock），只是状态栏多个图标 */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun cancel(strategyId: String) {
        val pendingIntent = buildTriggerIntent(strategyId, 0L)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** 先撤后立：禁用与删除都会留下孤儿闹钟，重排时必须把已启用的整批过一遍 */
    fun rescheduleAll(strategies: List<ScheduleStrategy>) {
        strategies.forEach { strategy ->
            cancel(strategy.id)
            if (strategy.enabled) scheduleNext(strategy)
        }
    }

    fun computeNextTrigger(strategy: ScheduleStrategy, afterEpochMs: Long = 0L): ZonedDateTime? =
        nextTriggerOf(strategy, systemNow(), afterEpochMs)

    private fun buildTriggerIntent(strategyId: String, scheduledTimeMs: Long): PendingIntent {
        val intent = Intent(ACTION_SCHEDULE_TRIGGER).apply {
            setClassName(context, ScheduleReceiver::class.java.name)
            putExtra(EXTRA_STRATEGY_ID, strategyId)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTimeMs)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(strategyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** setAlarmClock 要的展示入口：用户点状态栏闹钟图标时开主界面。所有策略共用一个即可 */
    private fun buildShowIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** PendingIntent 只按 requestCode + Intent 过滤器区分，extras 不参与——同一策略必须落同一个码 */
    private fun requestCode(strategyId: String): Int = strategyId.hashCode() and 0x7FFFFFFF

    companion object {
        const val ACTION_SCHEDULE_TRIGGER = "com.aliothmoon.maafw.SCHEDULE_TRIGGER"
        const val EXTRA_STRATEGY_ID = "strategy_id"
        const val EXTRA_SCHEDULED_TIME = "scheduled_time"
    }
}
