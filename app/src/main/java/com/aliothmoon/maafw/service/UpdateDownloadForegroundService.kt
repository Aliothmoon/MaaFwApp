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
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.DownloadState
import com.aliothmoon.maafw.notification.UpdateDownloadNotification
import com.aliothmoon.maafw.notification.UpdateDownloadProgressSnapshot
import com.aliothmoon.maafw.notification.UpdateDownloadProgressSnapshots
import com.aliothmoon.maafw.notification.canRequestPromotedOngoing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * 应用更新下载的前台服务
 *
 * 跟 [com.aliothmoon.maafw.service.RunForegroundService] 同型:
 *  - 进程保活:app 进后台后,OkHttp 拉到一半不至于被 system 整个回收
 *  - Live Update:Android 16+ 用 [NotificationCompat.ProgressStyle] + `setRequestPromotedOngoing`
 *    走 Live Update;状态来自 [UpdateDownloadNotification.state]
 *
 * `Idle` / `Complete` / `Failed` 三态会刷新一次然后 `stopForeground + stopSelf`:
 *  - Idle:用户取消 / 立刻 finish
 *  - Complete / Failed:进系统安装器前留一条最终条目,留痕
 */
class UpdateDownloadForegroundService : Service() {

    private val notification: UpdateDownloadNotification by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    /** 1Hz 节流:OkHttp 的 1 MiB 间隔在百兆级下载里会刷几百条通知 */
    private var lastPostedAt = 0L

    private var channelReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 5 秒内必须 startForeground,先抢一个占位通知
        startAsForeground(buildNotification(initialSnapshot()))
        // 若进服务时已经 Idle(极短竞态),直接退
        if (notification.state.value is DownloadState.Idle) {
            stopNow()
            return
        }
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统可能只走 onStartCommand;FGS 提升要在这里再保一次
        startAsForeground(buildNotification(snapshotFromState()))
        observe()
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
            notification.state
                .collectLatest { state ->
                    when (state) {
                        DownloadState.Idle -> stopNow()
                        is DownloadState.Complete, is DownloadState.Failed -> {
                            // 终态:刷新一次,留痕,然后退
                            post(buildNotification(snapshotFromState()))
                            delay(STOP_DELAY_MS)  // 给系统时间接收 autoCancel 末端
                            stopNow(removeNotification = false)
                        }
                        is DownloadState.Downloading -> {
                            throttledPost(state)
                        }
                    }
                }
        }
    }

    private suspend fun throttledPost(@Suppress("UNUSED_PARAMETER") state: DownloadState.Downloading) {
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_UPDATE_INTERVAL_MS - (now - lastPostedAt)
        if (wait > 0) delay(wait)
        // 节流期间状态可能跳到终态,留给下一次 collectLatest 自己跑
        if (notification.state.value !is DownloadState.Downloading) return
        lastPostedAt = SystemClock.elapsedRealtime()
        post(buildNotification(snapshotFromState()))
    }

    private fun initialSnapshot(): UpdateDownloadProgressSnapshot =
        UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Downloading("", 0L, -1L),
            versionLabel = { "" },
            sizeLabel = { "" },
            errorMessage = null,
        )

    private fun snapshotFromState(): UpdateDownloadProgressSnapshot =
        UpdateDownloadProgressSnapshots.from(
            state = notification.state.value,
            versionLabel = { v -> v },
            sizeLabel = { bytes -> Formatter.formatShortFileSize(this, bytes.coerceAtLeast(0L)) },
            errorMessage = (notification.state.value as? DownloadState.Failed)?.message,
        )

    private fun ensureChannel() {
        if (channelReady) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                UpdateDownloadProgressSnapshots.CHANNEL_ID,
                getString(R.string.notification_channel_update_download),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_update_download_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelReady = true
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snapshot: UpdateDownloadProgressSnapshot): Notification {
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressIndeterminate(snapshot.indeterminate)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(this, R.drawable.ic_progress_tracker),
            )
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(UpdateDownloadProgressSnapshots.PROGRESS_MAX)
                    .setColor(snapshot.barColor),
            )
        if (!snapshot.indeterminate) {
            style.setProgress(snapshot.progress)
        }
        return NotificationCompat.Builder(this, UpdateDownloadProgressSnapshots.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(snapshot.barColor)
            .setContentTitle(getString(snapshot.titleRes, snapshot.shortCriticalText ?: ""))
            .setContentText(snapshot.contentText.takeIf { it.isNotBlank() })
            .setProgress(
                UpdateDownloadProgressSnapshots.PROGRESS_MAX,
                snapshot.progress,
                snapshot.indeterminate,
            )
            .setStyle(style)
            .setContentIntent(contentIntent())
            .setOngoing(snapshot.ongoing)
            .setAutoCancel(snapshot.autoCancel)
            .setRequestPromotedOngoing(notificationManager.canRequestPromotedOngoing())
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                snapshot.shortCriticalText?.takeIf { it.isNotBlank() }?.let { setShortCriticalText(it) }
            }
            .build()
    }

    private fun post(notification: Notification) {
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "Failed to update download notification") }
    }

    private fun stopNow(removeNotification: Boolean = true) {
        val flag = if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
        runCatching { stopForeground(flag) }
        runCatching { stopSelf() }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 9101
        const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /** Complete/Failed 终端条目被系统接收并展示后留点时间清场 */
        const val STOP_DELAY_MS = 500L
    }
}
