package com.aliothmoon.maafw.schedule
import com.aliothmoon.maafw.MaaDispatchers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.ACTION_SCHEDULE_TRIGGER
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_RETRY_COUNT
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
        val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        Timber.i("Schedule alarm fired: %s, retry=%d", strategyId, retryCount)

        val serviceIntent = Intent(context, ScheduleExecutionService::class.java).apply {
            action = ACTION_SCHEDULE_TRIGGER
            putExtra(EXTRA_STRATEGY_ID, strategyId)
            putExtra(EXTRA_SCHEDULED_TIME, scheduledTime)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
        }
        // onReceive 一返回闹钟的锁就撤了，服务的 onStartCommand 还没轮到主线程，中间这段靠接力锁；
        // 成功路径不放，交给超时，服务那边自己再持一把
        val handoff = ScheduleWakeLock.acquire(context, HANDOFF_WAKE_TIMEOUT_MS)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // 正常路径不该到这：精确闹钟（setExactAndAllowWhileIdle）在豁免内
            // 前台服务的后台启动限制。真到了这里，服务不会跑、scheduleNext 也不会被调用，
            // 闹钟链就此断掉——所以在这里补注册下一环，让下次还有机会恢复。
            // 不只接 IllegalStateException：ROM 拦截时也可能是 SecurityException，漏接同样断链
            Timber.e(e, "Failed to start foreground service; rescheduling next alarm: %s", strategyId)
            rescheduleAfterFailure(strategyId, scheduledTime, retryCount, e.message, handoff)
        }
    }

    private fun rescheduleAfterFailure(
        strategyId: String,
        scheduledTime: Long,
        retryCount: Int,
        reason: String?,
        handoff: PowerManager.WakeLock,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + MaaDispatchers.IO + scheduleReceiverExceptionHandler).launch {
            try {
                val koin = GlobalContext.get()
                val store: ScheduleStrategyStore = koin.get()
                val alarms: ScheduleAlarmManager = koin.get()
                val loaded = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
                    store.isLoaded.first { it }
                }
                if (loaded == null) {
                    // 规则读不出来算不出下一环，只能隔一会儿把这一次再投一遍；重试用尽就转慢速接链，
                    // 两样都不挂，这条链就断在这里
                    if (!alarms.scheduleRetry(strategyId, scheduledTime, retryCount)) {
                        alarms.scheduleReconnect(strategyId, scheduledTime)
                    }
                    return@launch
                }
                val strategy = store.findById(strategyId)
                if (strategy == null) {
                    // 规则表读出了却没有它：删了或规则文件被重置，撤掉槽位
                    Timber.w("Schedule strategy no longer exists: %s", strategyId)
                    alarms.forget(strategyId)
                } else {
                    alarms.scheduleNext(strategy, scheduledTime)
                    store.recordTrigger(strategyId, TriggerResult.FAILED_SERVICE_START, reason)
                    // 用户查「昨晚为什么没跑」看的是触发日志，只记在规则上等于没记
                    koin.get<ScheduleTriggerLog>().append(
                        TriggerLogEntry(
                            strategyId = strategyId,
                            strategyName = strategy.name,
                            scheduledAt = scheduledTime,
                            actualAt = System.currentTimeMillis(),
                            result = TriggerResult.FAILED_SERVICE_START,
                            detail = reason,
                        ),
                    )
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
