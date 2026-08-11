package com.aliothmoon.maafw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * 执行期间把 app 进程钉成前台
 *
 * 不是为了显示进度——是为了活着：app 进程一死，特权进程的看门狗随即自杀并释放虚拟屏，
 * 表现成「任务跑一半自己停了」。实测 MIUI 的 ProcessManager 会对 Adj=905 的空进程
 * 直接 force-stop（`SwipeUpClean: force-stop <pkg> Adj=905`），前台服务是唯一挡得住的一层
 *
 * 只提供 [start] 不提供外部 stop：`startForegroundService` 之后若 `stopService` 抢在
 * onCreate 之前到达，系统会因 startForeground 未调用直接杀进程。终态退出由本服务自己
 * 观察 [RunnerPort.state] 完成
 */
class RunForegroundService : Service() {

    private val runnerPort: RunnerPort by inject()
    private val focusDispatcher: FocusDispatcher by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    /** 通知刷新节流的上次落点；MaaFramework 的进度回调能一秒来好几条 */
    private var lastUpdateAt = 0L

    private var focusChannelReady = false
    private var focusNotificationSeq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 必须先 startForeground 再判终态：慢一步就是 ForegroundServiceDidNotStartInTimeException
        val initial = runnerPort.state.value
        startAsForeground(buildNotification(initial))
        if (!initial.phase.isBusy) {
            stopNow()
            return
        }
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统可能只走 onStartCommand；FGS 提升要在这里再保一次
        val snapshot = runnerPort.state.value
        startAsForeground(buildNotification(snapshot))
        if (!snapshot.phase.isBusy) {
            stopNow()
        } else {
            observe()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch {
            launch {
                runnerPort.state
                    .collect { state ->
                        if (!state.phase.isBusy) {
                            stopNow()
                            return@collect
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return@collect
                        lastUpdateAt = now
                        notify(buildNotification(state))
                    }
            }
            launch { observeFocusNotifications() }
        }
    }

    /**
     * PI 声明 `display: notification` 的模板消息走系统通知
     *
     * 接在这里而不是 UI 层：那一档的用意就是「应用在后台时也收得到」，
     * 而 SessionEffect 要 Activity 在场才消费得掉。本服务在整轮执行期都活着，正好覆盖
     *
     * 收的是 [FocusDispatcher] 补完之后的正文，不是原始事件——`$key`、文件路径、
     * `{name}` 这些形态得先补完，否则推给用户的是没处理过的模板
     */
    private suspend fun observeFocusNotifications() {
        focusDispatcher.resolved.collect { focus ->
            if (FocusChannel.Notification !in focus.channels) return@collect
            ensureFocusChannel()
            val notification = NotificationCompat.Builder(this, FOCUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_focus_title))
                .setContentText(focus.content)
                // 模板正文可以很长，折叠成一行就没意义了
                .setStyle(NotificationCompat.BigTextStyle().bigText(focus.content))
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            // 逐条独立 id：这些是各自成立的消息，后一条不该顶掉前一条
            runCatching { notificationManager.notify(nextFocusNotificationId(), notification) }
                .onFailure { Timber.w(it, "Failed to post focus notification") }
        }
    }

    /** 用完才建：不带 notification 模板的 PI 不该在系统设置里多出一个空频道 */
    private fun ensureFocusChannel() {
        if (focusChannelReady) return
        val channel = NotificationChannel(
            FOCUS_CHANNEL_ID,
            getString(R.string.notification_channel_focus),
            // 与常驻通知相反，这一档是 PI 作者明确要求推给用户的，得出声
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notification_channel_focus_desc) }
        notificationManager.createNotificationChannel(channel)
        focusChannelReady = true
    }

    private fun nextFocusNotificationId(): Int =
        FOCUS_NOTIFICATION_ID_BASE + (focusNotificationSeq++ % FOCUS_NOTIFICATION_ID_SLOTS)

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_run),
            // LOW：常驻通知不该每次刷新都出声，但不能用 MIN——那样进不了状态栏，
            // 部分 ROM 会连带认为前台服务不成立
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_run_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: RunnerState): Notification {
        val execution = state.activeExecution
        val title = when (state.phase) {
            RunnerPhase.Preparing -> getString(R.string.notification_run_preparing)
            RunnerPhase.Stopping -> getString(R.string.notification_run_stopping)
            else -> getString(R.string.notification_run_running)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (execution != null && execution.totalTaskCount > 0) {
            val done = execution.completedTaskCount.coerceIn(0, execution.totalTaskCount)
            builder.setContentText(
                listOfNotNull(
                    "$done/${execution.totalTaskCount}",
                    execution.currentTaskName,
                ).joinToString(" · "),
            )
            builder.setProgress(execution.totalTaskCount, done, state.phase == RunnerPhase.Preparing)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 通知权限被拒时 notify/cancel 会抛 SecurityException，不能让它掀翻 FGS 主线程 */
    private fun notify(notification: Notification) {
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "Failed to update run notification") }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "run_execution"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        private const val FOCUS_CHANNEL_ID = "run_focus"

        /** 与 [NOTIFICATION_ID] 隔开一段，循环取用；一轮里堆几十条通知本身就是 PI 配错了 */
        private const val FOCUS_NOTIFICATION_ID_BASE = 1100
        private const val FOCUS_NOTIFICATION_ID_SLOTS = 20

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RunForegroundService::class.java))
            }.onFailure { Timber.w(it, "Failed to start foreground service") }
        }
    }
}
