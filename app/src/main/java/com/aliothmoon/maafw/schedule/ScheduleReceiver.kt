package com.aliothmoon.maafw.schedule
import com.aliothmoon.maafw.MaaDispatchers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.ACTION_SCHEDULE_TRIGGER
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_SCHEDULED_TIME
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_STRATEGY_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import timber.log.Timber

/** 闹钟到点：把活交给前台服务，广播里干不了长活 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULE_TRIGGER) return
        val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID) ?: return
        val scheduledTime = intent.getLongExtra(EXTRA_SCHEDULED_TIME, 0L)
        Timber.i("Schedule alarm fired: %s", strategyId)

        val serviceIntent = Intent(context, ScheduleExecutionService::class.java).apply {
            action = ACTION_SCHEDULE_TRIGGER
            putExtra(EXTRA_STRATEGY_ID, strategyId)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTime)
        }
        // onReceive 一返回闹钟的锁就撤了，服务的 onStartCommand 还没轮到主线程，中间这段靠接力锁；
        // 成功路径不放，交给超时，服务那边自己再持一把
        val handoff = ScheduleWakeLock.acquire(context, HANDOFF_WAKE_TIMEOUT_MS)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: IllegalStateException) {
            // 正常路径不该到这：闹钟走 setExactAndAllowWhileIdle / setAlarmClock，两者都豁免
            // 前台服务的后台启动限制。真到了这里，服务不会跑、scheduleNext 也不会被调用，
            // 闹钟链就此断掉——所以在这里补注册下一环，让下次还有机会恢复
            Timber.e(e, "Failed to start foreground service; rescheduling next alarm: %s", strategyId)
            rescheduleAfterFailure(strategyId, scheduledTime, e.message, handoff)
        }
    }

    private fun rescheduleAfterFailure(
        strategyId: String,
        scheduledTime: Long,
        reason: String?,
        handoff: PowerManager.WakeLock,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + MaaDispatchers.IO).launch {
            try {
                val koin = GlobalContext.get()
                val store: ScheduleStrategyStore = koin.get()
                val alarms: ScheduleAlarmManager = koin.get()
                val loaded = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
                    store.isLoaded.first { it }
                }
                val strategy = if (loaded == null) null else store.findById(strategyId)
                if (strategy != null) {
                    store.recordTrigger(strategyId, TriggerResult.FAILED_SERVICE_START, reason)
                    alarms.scheduleNext(strategy, scheduledTime)
                }
            } finally {
                ScheduleWakeLock.release(handoff)
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val STORE_READY_TIMEOUT_MS = 5_000L
        const val HANDOFF_WAKE_TIMEOUT_MS = 10_000L
    }
}
