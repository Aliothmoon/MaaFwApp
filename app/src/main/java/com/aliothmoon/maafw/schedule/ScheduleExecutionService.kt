package com.aliothmoon.maafw.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.ACTION_SCHEDULE_TRIGGER
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_SCHEDULED_TIME
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_STRATEGY_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * 闹钟落地后的执行壳
 *
 * **当前不触发任何 MaaFramework 执行**：只把 app 叫醒、记一条触发日志、接上下一次闹钟
 * 接执行时改的是 [handleTrigger] 里那一段，闹钟链与记账都不用动——发起用例已经落在
 * [com.aliothmoon.maafw.runner.RunLauncher]（进程级，与首页 Start 共用同一条），
 * 这里 inject 它即可，不必复制 SessionViewModel 的那段
 *
 * 必须是前台服务：广播里 5 秒就被回收，而 12+ 的后台启动限制只对 exact 闹钟发出的
 * 广播开口子（见 [ScheduleAlarmManager.scheduleNext]）
 */
class ScheduleExecutionService : Service() {

    private val store: ScheduleStrategyStore by inject()
    private val alarms: ScheduleAlarmManager by inject()
    private val triggerLog: ScheduleTriggerLog by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 生命周期跟在途触发数走，不跟最后一个 startId：并发触发时后到的收尾会把前一条掐掉 */
    private val inFlight = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5 秒内必须 startForeground，等不了协程调度
        ensureChannel()
        startAsForeground(buildNotification(getString(R.string.notification_schedule_triggered)))

        val strategyId = intent?.getStringExtra(EXTRA_STRATEGY_ID)
        if (intent?.action != ACTION_SCHEDULE_TRIGGER || strategyId.isNullOrEmpty()) {
            Timber.w("定时服务收到无效 intent: action=%s", intent?.action)
            stopIfIdle()
            return START_NOT_STICKY
        }

        val scheduledTime = intent.getLongExtra(EXTRA_SCHEDULED_TIME, 0L)
        // 必须先于 launch：协程调度前计数还是 0，会被并发触发的收尾停掉
        inFlight.incrementAndGet()
        serviceScope.launch {
            try {
                handleTrigger(strategyId, scheduledTime)
            } finally {
                inFlight.decrementAndGet()
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleTrigger(strategyId: String, scheduledTimeMs: Long) {
        val strategy = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
            store.isLoaded.first { it }
            store.findById(strategyId)
        }
        val now = System.currentTimeMillis()
        if (strategy == null) {
            Timber.w("定时策略已不存在: %s", strategyId)
            triggerLog.append(
                TriggerLogEntry(
                    strategyId = strategyId,
                    strategyName = strategyId,
                    scheduledAt = scheduledTimeMs,
                    actualAt = now,
                    result = TriggerResult.FAILED_VALIDATION,
                ),
            )
            store.recordTrigger(strategyId, TriggerResult.FAILED_VALIDATION, triggeredAt = now)
            return
        }

        // 执行未接入：这里将来 inject [com.aliothmoon.maafw.runner.RunLauncher] 并按策略绑定的
        // 运行配置发起一轮，其余不动。缺的只剩 ScheduleStrategy 上的 runConfigurationId 字段与盘上数据迁移
        triggerLog.append(
            TriggerLogEntry(
                strategyId = strategy.id,
                strategyName = strategy.name,
                scheduledAt = scheduledTimeMs,
                actualAt = now,
                result = TriggerResult.TRIGGERED,
            ),
        )
        store.recordTrigger(strategy.id, TriggerResult.TRIGGERED, triggeredAt = now)
        alarms.scheduleNext(strategy, scheduledTimeMs)
    }

    /** 有在途触发就不摘 FGS：停了会把其他并发触发一起带走 */
    private fun stopIfIdle() {
        if (inFlight.get() > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_schedule),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_schedule_desc)
                setShowBadge(false)
            },
        )
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_schedule_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "schedule_execution"
        const val NOTIFICATION_ID = 1002
        const val STORE_READY_TIMEOUT_MS = 5_000L
    }
}
