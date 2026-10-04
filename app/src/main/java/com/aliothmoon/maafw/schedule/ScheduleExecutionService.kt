package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.MaaDispatchers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.notification.canRequestPromotedOngoing
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.ACTION_SCHEDULE_TRIGGER
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_RETRY_COUNT
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_SCHEDULED_TIME
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager.Companion.EXTRA_STRATEGY_ID
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.service.SpecialUseFgsGate
import com.aliothmoon.maafw.runner.RunProgress
import com.aliothmoon.maafw.runner.ScheduleRunOptions
import com.aliothmoon.maafw.runner.RunRequestId
import com.aliothmoon.maafw.runner.RunSignals
import com.aliothmoon.maafw.runner.RunStepSink
import com.aliothmoon.maafw.runner.RunTrigger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
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
    private val appSettings: AppSettingsManager by inject()

    /** 记账写盘的 IOException 不能把进程带崩：那会连同刚受理的这一轮一起杀掉 */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + MaaDispatchers.IO + CoroutineExceptionHandler { _, e ->
            Timber.e(e, "Schedule trigger handling failed")
        },
    )

    /** 生命周期跟在途触发数走，不跟最后一个 startId：并发触发时后到的收尾会把前一条掐掉 */
    private val inFlight = AtomicInteger(0)

    /**
     * 最近一次 onStartCommand 的 startId；停服务只认它
     *
     * 已排队还没轮到 onStartCommand 的 startForegroundService 不在 [inFlight] 里，
     * 裸 stopSelf 会把它连同 serviceScope 一起掐掉，那一发的续排就丢了
     */
    private val latestStartId = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        // 5 秒内必须 startForeground，等不了协程调度
        ensureChannel()
        startAsForeground(buildNotification(getString(R.string.notification_schedule_triggered)))

        // 倒计时上的两个按钮回到这里；不新起一轮，只把信号置位。
        // 它们也占了 latestStartId，那一轮已经收尾的话得由这里停
        when (intent?.action) {
            ACTION_START_NOW -> {
                signalsByStrategy[intent.getStringExtra(EXTRA_STRATEGY_ID)]?.requestStartNow()
                stopIfIdle()
                return START_NOT_STICKY
            }

            ACTION_CANCEL_RUN -> {
                signalsByStrategy[intent.getStringExtra(EXTRA_STRATEGY_ID)]?.requestCancel()
                stopIfIdle()
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
        val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        // 锁覆盖到 launch 受理为止：亮屏解锁、倒计时、投递都在这段，之后的保活归 RunForegroundService
        val wakeLock = ScheduleWakeLock.acquire(this, TRIGGER_WAKE_TIMEOUT_MS)
        // 必须先于 launch：协程调度前计数还是 0，会被并发触发的收尾停掉
        inFlight.incrementAndGet()
        serviceScope.launch {
            try {
                handleTrigger(strategyId, scheduledTime, retryCount)
            } finally {
                ScheduleWakeLock.release(wakeLock)
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

    private suspend fun handleTrigger(strategyId: String, scheduledTimeMs: Long, retryCount: Int) {
        val ready = withTimeoutOrNull(STORE_READY_TIMEOUT_MS) {
            store.isLoaded.first { it }
            // 设置读盘是异步的，投递前得等到位：runMode、分辨率、提权后端都在里面，
            // 早一步拿到的是默认值——后台模式的用户会被当成前台模式拦下来
            appSettings.loaded.first { it }
        } != null
        val now = System.currentTimeMillis()
        if (!ready) {
            handleDataUnavailable(strategyId, scheduledTimeMs, retryCount, now)
            return
        }
        val strategy = store.findById(strategyId)
        if (strategy == null) {
            // 规则表已读出却没有它：删了，或规则文件损坏被重置。撤掉槽位，不再续，
            // 否则孤儿闹钟每响一次都来记一条失败
            Timber.w("Schedule strategy no longer exists: %s", strategyId)
            alarms.forget(strategyId)
            triggerLog.append(
                TriggerLogEntry(
                    strategyId = strategyId,
                    strategyName = strategyId,
                    scheduledAt = scheduledTimeMs,
                    actualAt = now,
                    result = TriggerResult.FAILED_VALIDATION,
                ),
            )
            return
        }
        if (!strategy.enabled) {
            // 关掉规则时闹钟已撤，能走到这是撤之前就已投递的那一发
            Timber.i("Schedule strategy disabled, skipped: %s", strategyId)
            return
        }
        if (retryCount >= ScheduleAlarmManager.RECONNECT_RETRY_COUNT) {
            // 慢速接链的那一发：原定那一次早已放弃，规则读得到了就只把链接回来
            Timber.i("Schedule rules readable again, chain restored: %s", strategyId)
            alarms.scheduleNext(strategy, scheduledTimeMs)
            return
        }

        // 先续再跑：launch 里有亮屏解锁、30 秒倒计时和抢占，这段里进程被杀，
        // 放在后面的续排就永远轮不到。重投同一时刻由 requestId 去重，不会多跑；
        // 去重只在内存里，所以还要记一笔已投递，免得整批重排把这一次再挂回来
        alarms.markDelivered(strategy.id, scheduledTimeMs)
        alarms.scheduleNext(strategy, scheduledTimeMs)

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
                configurationId = RunConfigurationId(strategy.runConfigurationId),
                // 策略 + 原定时刻唯一确定一次触发；系统重投同一个 PendingIntent 时算得出同一个 id
                requestId = RunRequestId($$"${strategy.id}@$scheduledTimeMs"),
                force = strategy.forceStart,
                steps = RunStepSink {
                    steps += TriggerStep(
                        it.hookId,
                        it.outcome.toTriggerStepOutcome()
                    )
                },
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
            // 第一次投递已经记过账了，这里不再记，否则会多一条记录
            Timber.i("Schedule %s duplicate delivery, dropped", strategy.id)
            return
        }
        if (outcome.result != TriggerResult.STARTED) {
            Timber.w("Schedule %s did not start: %s", strategy.id, launchResult)
        }
        val frozen = outcome.detail?.resolve(this)?.takeIf { it.isNotBlank() }

        triggerLog.append(
            TriggerLogEntry(
                strategyId = strategy.id,
                strategyName = strategy.name,
                scheduledAt = scheduledTimeMs,
                actualAt = now,
                result = outcome.result,
                failureReason = outcome.failureReason,
                detail = frozen,
                steps = steps,
            ),
        )
        store.recordTrigger(strategy.id, outcome.result, message = frozen, triggeredAt = now)
    }

    /**
     * 规则或设置在时限内没读出来：这不等于规则被删了
     *
     * 当成被删处理就既不跑也不续，这条规则从此不再响；所以隔一会儿把同一次再投一遍。
     * 重试用尽就放弃这一次：规则此刻读得到就接上下一环；规则文件本身还没读出来，
     * 区分不了删没删，只能转慢速接链，读到为止
     */
    private suspend fun handleDataUnavailable(
        strategyId: String,
        scheduledTimeMs: Long,
        retryCount: Int,
        now: Long,
    ) {
        if (retryCount >= ScheduleAlarmManager.RECONNECT_RETRY_COUNT) {
            // 已在慢速接链：放弃那条日志已经写过，这里每 15 分钟一发，只进 Timber
            Timber.w("Schedule rules still unavailable: %s", strategyId)
            alarms.scheduleReconnect(strategyId, scheduledTimeMs)
            return
        }
        val retried = alarms.scheduleRetry(strategyId, scheduledTimeMs, retryCount)
        val storeLoaded = store.isLoaded.value
        val strategy = store.findById(strategyId)
        var reconnecting = false
        if (!retried) {
            when {
                strategy != null -> alarms.scheduleNext(strategy, scheduledTimeMs)
                // 规则表读出了却没有它：删了，不再续
                storeLoaded -> alarms.forget(strategyId)
                else -> {
                    alarms.scheduleReconnect(strategyId, scheduledTimeMs)
                    reconnecting = true
                }
            }
        }
        Timber.w("Schedule data unavailable: %s, attempt=%d, retried=%s", strategyId, retryCount, retried)
        triggerLog.append(
            TriggerLogEntry(
                strategyId = strategyId,
                strategyName = strategy?.name ?: strategyId,
                scheduledAt = scheduledTimeMs,
                actualAt = now,
                result = TriggerResult.FAILED_VALIDATION,
                detail = when {
                    retried -> getString(R.string.schedule_detail_data_unavailable_retry, retryCount + 1)
                    reconnecting -> getString(R.string.schedule_detail_data_unavailable_waiting)
                    else -> getString(R.string.schedule_detail_data_unavailable_gave_up)
                },
            ),
        )
    }

    /**
     * 有在途触发就不摘 FGS：停了会把其他并发触发一起带走
     *
     * 按 [latestStartId] 停：其后又有 startForegroundService 排进来就停不掉，交给那一发自己收尾
     */
    private fun stopIfIdle() {
        if (inFlight.get() > 0) return
        if (stopSelfResult(latestStartId.get())) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_schedule),
                // LOW：短暂 FGS 不该出声。MIN 进不了状态栏；Live Update 也只禁 MIN
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_schedule_desc)
                setShowBadge(false)
            },
        )
    }

    private fun startAsForeground(notification: Notification) {
        try {
            SpecialUseFgsGate.startForeground(this, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // specialUse 被系统拒绝：尽力继续触发，uid 转空闲后服务可能被系统停掉
            Timber.w(e, "ScheduleExecutionService: startForeground denied, continue without FGS")
        }
    }

    private fun updateNotification(text: String, strategyId: String, interruptible: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text, strategyId.takeIf { interruptible })
        )
    }

    /** [interruptibleStrategyId] 非 null 时挂上「立即开始 / 取消本次」两个动作 */
    private fun buildNotification(
        text: String,
        interruptibleStrategyId: String? = null
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_schedule_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setRequestPromotedOngoing(manager.canRequestPromotedOngoing())
            .setSilent(true)
            .setOnlyAlertOnce(true)
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
        /**
         * 倒计时通知上的两个动作；进来的 intent 只置信号，不起新一轮
         * 跟着 applicationId 走，与 [ScheduleAlarmManager.ACTION_SCHEDULE_TRIGGER] 一个口径——
         * 这两条目前只走显式 intent，撞不上，但 action 命名不该有两套
         */
        const val ACTION_START_NOW = BuildConfig.APPLICATION_ID + ".action.SCHEDULE_START_NOW"
        const val ACTION_CANCEL_RUN = BuildConfig.APPLICATION_ID + ".action.SCHEDULE_CANCEL_RUN"

        /** 在途触发的打断面，按策略 id 索引；并发触发各按各的 */
        private val signalsByStrategy = ConcurrentHashMap<String, RunSignals>()

        const val CHANNEL_ID = "schedule_execution"
        const val NOTIFICATION_ID = 1002
        const val STORE_READY_TIMEOUT_MS = 5_000L

        /** 超时只兜漏放；正常一次触发在倒计时 30 秒加投递之内就放掉 */
        const val TRIGGER_WAKE_TIMEOUT_MS = 5 * 60_000L
    }
}
