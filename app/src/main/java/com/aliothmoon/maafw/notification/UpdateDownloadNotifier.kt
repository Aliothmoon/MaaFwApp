package com.aliothmoon.maafw.notification

import timber.log.Timber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R


/**
 * 应用更新下载进度的系统通知
 *
 * 与「跑完了 / 出错了」这种事件通知 ([RunEventNotifier]) 分开：
 *  - 独立 channel [CHANNEL_ID]，重要性 LOW（不弹横幅、不响，进度条走通知栏常态即可）
 *  - 不受 `AppSettings.eventNotificationLevel` 控制——下载是用户主动点的，
 *    关了任务通知的人也想看到进度
 *  - 下载成功的瞬间交给系统安装器接管，progressive 通知 autoCancel 掉
 *  - 失败时切到一个静态失败条目，状态栏里留痕，方便用户回看
 *
 * 进度刷新的频率由 `OkHttpUpdateDownloader.PROGRESS_INTERVAL` 决定（1 MiB），
 * 这里只负责把 (downloaded, total) 推上系统栏，不做自己的节流
 */
class UpdateDownloadNotifier(context: Context) : UpdateDownloadNotification {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val managerCompat = NotificationManagerCompat.from(appContext)

    private var channelsReady = false

    /**
     * 开始一档下载
     *
     * [totalBytes] 未知时传 `-1L`，会用不定态进度条；服务端给 Content-Length 后第一帧
     * [SettingsViewModel.downloadUpdate] 的 onProgress 会切到定态
     */
    override fun start(version: String, totalBytes: Long) {
        if (!canPost()) return
        ensureChannels()
        val tap = openAppPendingIntent()
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_update_download_title, version))
            .setContentText(progressText(0L, totalBytes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText(0L, totalBytes)))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        applyProgress(builder, 0L, totalBytes)
        post(builder.build())
    }

    override fun progress(version: String, downloadedBytes: Long, totalBytes: Long) {
        if (!canPost()) return
        val tap = openAppPendingIntent()
        val title = appContext.getString(R.string.notification_update_download_title, version)
        val body = progressText(downloadedBytes, totalBytes)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        applyProgress(builder, downloadedBytes, totalBytes)
        post(builder.build())
    }

    /**
     * 下载成功 → 切一条静态「已下载，准备安装」并 autoCancel，让位给系统安装界面
     */
    override fun complete(version: String) {
        if (!canPost()) return
        ensureChannels()
        val tap = openAppPendingIntent()
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_update_download_complete_title, version))
            .setContentText(appContext.getString(R.string.notification_update_download_complete))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(R.string.notification_update_download_complete)),
            )
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        post(builder.build())
    }

    /**
     * 下载失败 → 切一条静态错误条目留痕，等用户自己滑掉
     */
    override fun failed(message: String) {
        if (!canPost()) return
        ensureChannels()
        val tap = openAppPendingIntent()
        val body = appContext.getString(R.string.notification_update_download_failed, message)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_update_download_failed_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        post(builder.build())
    }

    override fun cancel() {
        runCatching { manager.cancel(NOTIFY_ID) }
            .onFailure { Timber.w(it, "Failed to cancel update download notification") }
    }

    private fun canPost(): Boolean {
        val enabled = managerCompat.areNotificationsEnabled()
        if (!enabled) {
            Timber.w("Notification is disabled or missing POST_NOTIFICATIONS permission")
            return false
        }
        // Android 13+ 还需要单独通知权限；areNotificationsEnabled 已经覆盖到
        return true
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            NOTIFY_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun progressText(downloaded: Long, total: Long): String =
        if (total > 0L) {
            val d = shortSize(downloaded)
            val t = shortSize(total)
            appContext.getString(R.string.settings_update_progress_known, d, t)
        } else {
            appContext.getString(R.string.settings_update_progress_unknown, shortSize(downloaded))
        }

    private fun applyProgress(
        builder: NotificationCompat.Builder,
        downloaded: Long,
        total: Long,
    ) {
        if (total <= 0L) {
            builder.setProgress(0, 0, true) // indeterminate
        } else {
            val pct = ((downloaded.coerceAtMost(total) * 100L) / total).toInt()
            builder.setProgress(100, pct, false)
        }
    }

    private fun shortSize(bytes: Long): String =
        Formatter.formatShortFileSize(appContext, bytes.coerceAtLeast(0L))

    private fun post(notification: android.app.Notification) {
        runCatching { manager.notify(NOTIFY_ID, notification) }
            .onFailure { Timber.w(it, "Failed to post update download notification") }
    }

    /** 用到才建：全程关着通知的人不该在系统设置里多出一个空频道 */
    private fun ensureChannels() {
        if (channelsReady) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_update_download),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.notification_channel_update_download_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelsReady = true
    }

    private companion object {
        const val CHANNEL_ID = "maa_update_download"

        /**
         * 一个 id 跑完整个下载生命周期：start/progress 会刷这条，进度/完成/失败之间
         * 不会因为通知栏里多出几条一样的干扰。完成时 autoCancel 让 install 之前空出位置
         */
        const val NOTIFY_ID = 9101
    }
}
