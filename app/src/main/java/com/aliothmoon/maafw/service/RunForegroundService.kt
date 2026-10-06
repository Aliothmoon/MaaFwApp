package com.aliothmoon.maafw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.di.AppCoroutineScope
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.RunProgressSnapshots
import com.aliothmoon.maafw.notification.live.LiveBackend
import com.aliothmoon.maafw.notification.live.LiveCapabilityProbe
import com.aliothmoon.maafw.notification.live.RunNotificationContent
import com.aliothmoon.maafw.notification.live.RunNotificationFactory
import com.aliothmoon.maafw.notification.live.XmsfNetworkGate
import com.aliothmoon.maafw.notification.stringRes
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.FocusDialogController
import com.aliothmoon.maafw.runner.FocusDialogRequest
import com.aliothmoon.maafw.runner.RunLogRecorder
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.focusPlainText
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import timber.log.Timber

/**
 * 执行期间把 app 进程钉成前台
 *
 * 不是为了显示进度——是为了活着：app 进程一死，特权进程的看门狗随即自杀并释放虚拟屏，
 * 表现成「任务跑一半自己停了」。实测 MIUI 的 ProcessManager 会对 Adj=905 的空进程
 * 直接 force-stop（`SwipeUpClean: force-stop <pkg> Adj=905`），前台服务是唯一挡得住的一层
 *
 * 同一条通知顺带当运行通知：进度来自 [RunnerState]，状态句来自 [RunLogRecorder.liveUpdateStatus]，
 * 按用户选的展示方式（超级岛 / 实时更新 / 标准通知栏）由 [RunNotificationFactory] 拼。
 * 超级岛要先经 [XmsfNetworkGate] 断开小米推送的联网再发，拿到闸门前按标准通知栏发，免得被云端鉴权撤岛
 *
 * 只提供 [start] 不提供外部 stop：`startForegroundService` 之后若 `stopService` 抢在
 * onCreate 之前到达，系统会因 startForeground 未调用直接杀进程。终态退出由本服务自己
 * 观察 [RunnerPort.state] 完成
 */
class RunForegroundService : Service() {

    private val runnerPort: RunnerPort by inject()
    private val focusDispatcher: FocusDispatcher by inject()
    private val focusDialogController: FocusDialogController by inject()
    private val recorder: RunLogRecorder by inject()
    private val appSettings: AppSettingsManager by inject()
    private val liveProbe: LiveCapabilityProbe by inject()
    private val notificationFactory: RunNotificationFactory by inject()
    private val xmsfGate: XmsfNetworkGate by inject()
    private val appScope: CoroutineScope by inject(named<AppCoroutineScope>())

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    /** 通知刷新节流的上次落点；MaaFramework 的进度回调能一秒来好几条 */
    private var lastUpdateAt = 0L

    private var focusChannelReady = false
    private var focusNotificationSeq = 0
    private val modalNotificationIds = mutableMapOf<String, Int>()
    private var modalNotificationSeq = 0

    /** startForeground 被系统拒绝后本实例已 stopSelf，排队中的 start 不再重试 */
    private var foregroundDenied = false

    /** 超级岛：断网闸门是否已拿到；拿到之前按标准通知栏发 */
    private val islandReady = MutableStateFlow(false)
    private var islandGateJob: Job? = null

    /** 超级岛：本轮是否已浮出过一次，之后的刷新不再浮 */
    private var islandFloated = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannels()
        // 必须先 startForeground 再判终态：慢一步就是 ForegroundServiceDidNotStartInTimeException
        val initial = runnerPort.state.value
        if (!startAsForeground(buildNotification(initial, recorder.liveUpdateStatus.value))) return
        if (!initial.phase.isBusy) {
            stopNow()
            return
        }
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundDenied) return START_NOT_STICKY
        // 系统可能只走 onStartCommand；FGS 提升要在这里再保一次
        val snapshot = runnerPort.state.value
        if (!startAsForeground(buildNotification(snapshot, recorder.liveUpdateStatus.value))) {
            return START_NOT_STICKY
        }
        if (!snapshot.phase.isBusy) {
            stopNow()
        } else {
            observe()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching(::cancelModalNotifications)
        observeJob = null
        releaseIslandGate()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch {
            launch { observeProgress() }
            launch { observeFocusNotifications() }
            launch { observeFocusDialogRequests() }
        }
    }

    private suspend fun observeProgress() {
        var lastPostedPhase: RunnerPhase? = null
        // 展示方式与闸门也算输入：设置里换了方式、闸门拿到了，都要立刻重发一帧
        combine(
            runnerPort.state,
            recorder.liveUpdateStatus,
            appSettings.liveBackend,
            islandReady,
        ) { state, status, _, _ -> state to status }.collectLatest { (state, status) ->
            if (!state.phase.isBusy) {
                lastPostedPhase = null
                cancelModalNotifications()
                stopNow()
                return@collectLatest
            }
            val now = SystemClock.elapsedRealtime()
            val wait = MIN_UPDATE_INTERVAL_MS - (now - lastUpdateAt)
            if (wait > 0 && state.phase == lastPostedPhase) delay(wait)
            if (!runnerPort.state.value.phase.isBusy) {
                lastPostedPhase = null
                stopNow()
                return@collectLatest
            }
            lastUpdateAt = SystemClock.elapsedRealtime()
            lastPostedPhase = state.phase
            notify(buildNotification(state, status))
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
     *
     * 通知正文是纯文本面，Markdown 与 HTML 记号要先剥掉
     */
    private suspend fun observeFocusNotifications() {
        focusDispatcher.resolved.collect { focus ->
            if (FocusChannel.Modal in focus.channels) return@collect
            if (FocusChannel.Notification !in focus.channels) return@collect
            // 只有一张图的模板剥完记号什么都不剩，不发空通知
            val text = focusPlainText(focus.content).ifEmpty { return@collect }
            ensureFocusChannel()
            val notification = NotificationCompat.Builder(this, FOCUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_focus_title))
                .setContentText(text)
                // 模板正文可以很长，折叠成一行就没意义了
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            // 逐条独立 id：这些是各自成立的消息，后一条不该顶掉前一条
            runCatching { notificationManager.notify(nextFocusNotificationId(), notification) }
                .onFailure { Timber.w(it, "Failed to post focus notification") }
        }
    }

    private suspend fun observeFocusDialogRequests() {
        focusDialogController.requests.collect { requests ->
            val pendingRequests = requests
                .filter(FocusDialogRequest::modal)
                .associateBy(FocusDialogRequest::id)
            modalNotificationIds.keys
                .filterNot(pendingRequests::containsKey)
                .forEach(::cancelModalNotification)
            pendingRequests.forEach { (id, request) ->
                if (id !in modalNotificationIds) postModalNotification(id, request.content)
            }
        }
    }

    private fun postModalNotification(id: String, content: String) {
        ensureFocusChannel()
        val notification = NotificationCompat.Builder(this, FOCUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.focus_modal_notification_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent())
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        val notificationId = modalNotificationIds.getOrPut(id) {
            MODAL_NOTIFICATION_ID_BASE + (modalNotificationSeq++ % FOCUS_NOTIFICATION_ID_SLOTS)
        }
        runCatching { notificationManager.notify(notificationId, notification) }
            .onFailure {
                modalNotificationIds.remove(id)
                Timber.w(it, "Failed to post modal focus notification")
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

    private fun cancelModalNotification(id: String) {
        val notificationId = modalNotificationIds.remove(id) ?: return
        runCatching { notificationManager.cancel(notificationId) }
            .onFailure { Timber.w(it, "Failed to cancel modal focus notification") }
    }

    private fun cancelModalNotifications() {
        modalNotificationIds.keys.toList().forEach(::cancelModalNotification)
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseIslandGate()
        stopSelf()
    }

    /**
     * 这一帧实际用哪种方式发：选的是超级岛但闸门还没拿到，先按标准通知栏发并去拿闸门；
     * 中途换走了超级岛就把闸门还掉
     */
    private fun effectiveBackend(): LiveBackend {
        val backend = liveProbe.snapshot().backend
        if (backend != LiveBackend.HYPER_ISLAND) {
            releaseIslandGate()
            return backend
        }
        if (islandReady.value) return backend
        if (islandGateJob == null) {
            // 闸门要跑特权进程里的 shell，不能占主线程
            islandGateJob = serviceScope.launch(Dispatchers.IO) {
                xmsfGate.acquire()
                islandReady.value = true
            }
        }
        return LiveBackend.PLAIN
    }

    /**
     * 还闸门；拿闸门的协程还在路上就等它拿完再还，否则晚到的 acquire 会让 xmsf 一直断着
     *
     * 等待放到应用级作用域：服务的作用域此刻可能正在取消
     */
    private fun releaseIslandGate() {
        val job = islandGateJob ?: return
        islandGateJob = null
        islandReady.value = false
        islandFloated = false
        appScope.launch(Dispatchers.IO) {
            job.join()
            xmsfGate.release()
        }
    }

    /** 被拒时停服务，onDestroy 撤掉已发出的进度通知；任务本身仍在提权进程继续 */
    private fun startAsForeground(notification: Notification): Boolean {
        try {
            SpecialUseFgsGate.startForeground(this, NOTIFICATION_ID, notification)
            return true
        } catch (e: SecurityException) {
            // 预检放行但系统仍拒（如 appop 为 FOREGROUND）；AOSP 抛出前已清 fgRequired，
            // stopSelf 不会触发 ForegroundServiceDidNotStartInTimeException
            Timber.w(e, "RunForegroundService: startForeground denied, run without FGS")
            foregroundDenied = true
            stopSelf()
            return false
        }
    }

    private fun buildNotification(state: RunnerState, statusText: String?): Notification {
        val snapshot = RunProgressSnapshots.from(state.phase, state.activeExecution, statusText)
        val content = RunNotificationContent(
            title = getString(snapshot.title.stringRes),
            text = snapshot.contentText,
            progressLabel = snapshot.shortCriticalText,
            taskLabel = snapshot.taskLabel,
            statusLine = snapshot.statusLine,
            progress = snapshot.progress,
            indeterminate = snapshot.indeterminate,
            barColor = snapshot.barColor,
        )
        val backend = effectiveBackend()
        val firstFloat = backend == LiveBackend.HYPER_ISLAND && !islandFloated
        if (firstFloat) islandFloated = true
        return notificationFactory.build(content, backend, NOTIFICATION_ID, firstFloat)
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
        private const val NOTIFICATION_ID = 1001
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        private const val FOCUS_CHANNEL_ID = "run_focus"

        /** 与 [NOTIFICATION_ID] 隔开一段，循环取用；一轮里堆几十条通知本身就是 PI 配错了 */
        private const val FOCUS_NOTIFICATION_ID_BASE = 1100
        private const val MODAL_NOTIFICATION_ID_BASE = 1120
        private const val FOCUS_NOTIFICATION_ID_SLOTS = 20

        fun start(context: Context) {
            if (SpecialUseFgsGate.isDenied(context)) {
                Timber.w("RunForegroundService: specialUse appop denied, skip FGS start")
                return
            }
            runCatching {
                context.startForegroundService(Intent(context, RunForegroundService::class.java))
            }.onFailure { Timber.w(it, "Failed to start foreground service") }
        }
    }
}
