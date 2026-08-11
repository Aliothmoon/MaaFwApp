package com.aliothmoon.maafw.schedule
import com.aliothmoon.maafw.MaaDispatchers

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
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.ACTION_SCHEDULE_TRIGGER
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_SCHEDULED_TIME
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_STRATEGY_ID
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunProgress
import com.aliothmoon.maafw.runner.ScheduleRunOptions
import com.aliothmoon.maafw.runner.RunRequestId
import com.aliothmoon.maafw.runner.RunSignals
import com.aliothmoon.maafw.runner.RunStepSink
import com.aliothmoon.maafw.runner.RunTrigger
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
 * 闹钟落地后的执行壳：叫醒 app、发起一轮执行、记一条触发日志、接上下一次闹钟
 *
 * 发起走 [RunLauncher]，与首页 Start 是同一条：检查、环境挂载、屏障、收尾都在那边，
 * 这里只负责把结局翻成记账（[toScheduleOutcome]）
 *
 * **不等这一轮跑完**：`launch` 受理即返回，收尾由 RunLauncher 自己的协程守着。
 * 本服务随即摘掉 FGS，执行期的保活换成 `RunForegroundService`（由 `KeepAliveHook` 拉起）
 *
 * 必须是前台服务：广播里 5 秒就被回收，而 12+ 的后台启动限制只对 exact 闹钟发出的
 * 广播开口子（见 [ScheduleAlarmManager.scheduleNext]）
 */
class ScheduleExecutionService : Service() {

    private val store: ScheduleStrategyStore by inject()
    private val alarms: ScheduleAlarmManager by inject()
    private val triggerLog: ScheduleTriggerLog by inject()
    private val runLauncher: RunLauncher by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)

    /** 生命周期跟在途触发数走，不跟最后一个 startId：并发触发时后到的收尾会把前一条掐掉 */
    private val inFlight = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5 秒内必须 startForeground，等不了协程调度
        ensureChannel()
        startAsForeground(buildNotification(getString(R.string.notification_schedule_triggered)))

        // 倒计时上的两个按钮回到这里；不新起一轮，只把信号置位
        when (intent?.action) {
            ACTION_START_NOW -> {
                signalsByStrategy[intent.getStringExtra(EXTRA_STRATEGY_ID)]?.requestStartNow()
                return START_NOT_STICKY
            }

            ACTION_CANCEL_RUN -> {
                signalsByStrategy[intent.getStringExtra(EXTRA_STRATEGY_ID)]?.requestCancel()
                return START_NOT_STICKY
            }
        }

        val strategyId = intent?.getStringExtra(EXTRA_STRATEGY_ID)
        if (intent?.action != ACTION_SCHEDULE_TRIGGER || strategyId.isNullOrEmpty()) {
            Timber.w("Schedule service received invalid intent: action=%s", intent?.action)
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
            Timber.w("Schedule strategy no longer exists: %s", strategyId)
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

        val steps = mutableListOf<TriggerStep>()
        val signals = RunSignals()
        // 倒计时期间用户要能打断，而那会儿 Activity 多半不在——落点只能是本服务的通知
        signalsByStrategy[strategy.id] = signals

        val launchResult = try {
            runLauncher.launch(
                trigger = RunTrigger.Schedule(
                    strategy.id,
                    ScheduleRunOptions(
                        autoSleepAfterTask = strategy.autoSleepAfterTask,
                        skipAutoSleepIfAwake = strategy.skipAutoSleepIfAwake,
                        closeAppAfterTask = strategy.closeAppAfterTask,
                    ),
                ),
                configurationId = strategy.runConfigurationId?.let(::RunConfigurationId),
                // 策略 + 原定时刻唯一确定一次触发；系统重投同一个 PendingIntent 时算得出同一个 id
                requestId = RunRequestId("${'$'}{strategy.id}@${'$'}scheduledTimeMs"),
                force = strategy.forceStart,
                steps = RunStepSink { steps += TriggerStep(it.hookId, it.outcome.toTriggerStepOutcome()) },
                signals = signals,
                progress = RunProgress { _, detail ->
                    updateNotification(detail.resolve(this), strategy.id, interruptible = true)
                },
            )
        } finally {
            signalsByStrategy.remove(strategy.id)
            updateNotification(
                getString(R.string.notification_schedule_triggered),
                strategy.id,
                interruptible = false,
            )
        }
        val outcome = launchResult.toScheduleOutcome()
        if (outcome.result == TriggerResult.DUPLICATE) {
            // 第一次投递已经记过账也续过闹钟了，这里什么都不做，否则会多一条记录、多排一次
            Timber.i("Schedule %s duplicate delivery, dropped", strategy.id)
            return
        }
        if (outcome.result != TriggerResult.STARTED) {
            // 拦截原因是 UiText，落不进日志；细节只能进 Timber
            Timber.w("Schedule %s did not start: %s", strategy.id, launchResult)
        }

        triggerLog.append(
            TriggerLogEntry(
                strategyId = strategy.id,
                strategyName = strategy.name,
                scheduledAt = scheduledTimeMs,
                actualAt = now,
                result = outcome.result,
                failureReason = outcome.failureReason,
                steps = steps,
            ),
        )
        store.recordTrigger(strategy.id, outcome.result, triggeredAt = now)
        // 无论跑没跑起来都要续闹钟：断链之后这条规则就永远不会再响了
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

    private fun updateNotification(text: String, strategyId: String, interruptible: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, strategyId.takeIf { interruptible }))
    }

    /** [interruptibleStrategyId] 非 null 时挂上「立即开始 / 取消本次」两个动作 */
    private fun buildNotification(text: String, interruptibleStrategyId: String? = null): Notification {
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
            .apply {
                interruptibleStrategyId?.let { id ->
                    addAction(
                        0,
                        getString(R.string.run_countdown_start_now),
                        signalIntent(ACTION_START_NOW, id),
                    )
                    addAction(
                        0,
                        getString(R.string.run_countdown_cancel),
                        signalIntent(ACTION_CANCEL_RUN, id),
                    )
                }
            }
            .build()
    }

    private fun signalIntent(action: String, strategyId: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ScheduleExecutionService::class.java).apply {
                this.action = action
                putExtra(EXTRA_STRATEGY_ID, strategyId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** 倒计时通知上的两个动作；进来的 intent 只置信号，不起新一轮 */
        const val ACTION_START_NOW = "com.aliothmoon.maafw.action.SCHEDULE_START_NOW"
        const val ACTION_CANCEL_RUN = "com.aliothmoon.maafw.action.SCHEDULE_CANCEL_RUN"

        /** 在途触发的打断面，按策略 id 索引；并发触发各按各的 */
        private val signalsByStrategy = java.util.concurrent.ConcurrentHashMap<String, RunSignals>()

        const val CHANNEL_ID = "schedule_execution"
        const val NOTIFICATION_ID = 1002
        const val STORE_READY_TIMEOUT_MS = 5_000L
    }
}
