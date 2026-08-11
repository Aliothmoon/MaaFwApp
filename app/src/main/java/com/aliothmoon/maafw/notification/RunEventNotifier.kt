package com.aliothmoon.maafw.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.EventNotificationLevel
import com.aliothmoon.maafw.settings.AppSettingsManager
import timber.log.Timber

/**
 * 运行事件的系统通知：一轮跑完了、跑挂了
 *
 * 与 `RunForegroundService` 的两种通知分工：那边一条是保活载体（常驻、静音、进度条），
 * 一条是 PI 作者写给用户的 focus 消息；这边是外壳自己对整轮结局的播报，
 * 用户可以在设置里整档关掉而不影响前两者
 *
 * 两个 channel 对应两档重要性。channel 一旦建出来重要性就归用户管，app 改不动——
 * 所以不是一个 channel 配两种 priority，而是切 channel
 */
class RunEventNotifier(
    context: Context,
    private val appSettingsManager: AppSettingsManager,
) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var channelsReady = false

    /** 整轮结局；[isError] 只改配色与分类，发不发由档位决定 */
    fun notifyRunFinished(title: String, text: String, isError: Boolean) {
        send(title, text, ID_RUN_RESULT, isError)
    }

    fun notifyTest(title: String, text: String) {
        send(title, text, ID_RUN_RESULT, isError = false)
    }

    private fun send(title: String, text: String, notifyId: Int, isError: Boolean) {
        val level = appSettingsManager.eventNotificationLevel.value
        if (level == EventNotificationLevel.OFF) return

        // 权限被拒时 notify 会抛 SecurityException；先问一句比接异常干净
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Timber.w("Notification is disabled or missing POST_NOTIFICATIONS permission")
            return
        }
        ensureChannels()

        val high = level == EventNotificationLevel.HIGH
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            // notifyId 兼作 requestCode，免得不同通知的 Intent 互相覆盖
            notifyId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(
            appContext,
            if (high) CHANNEL_HIGH else CHANNEL_DEFAULT,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // 失败清单可以很长，折叠成一行就看不出是哪几个任务挂了
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply { if (high) setDefaults(NotificationCompat.DEFAULT_ALL) }
            .build()

        runCatching { manager.notify(notifyId, notification) }
            .onFailure { Timber.w(it, "Failed to post run event notification") }
    }

    /** 用到才建：整档关着的用户不该在系统设置里多出两个空频道 */
    private fun ensureChannels() {
        if (channelsReady) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_DEFAULT,
                    appContext.getString(R.string.notification_event_channel_silent_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = appContext.getString(R.string.notification_event_channel_silent_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(
                    CHANNEL_HIGH,
                    appContext.getString(R.string.notification_event_channel_popup_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = appContext.getString(R.string.notification_event_channel_popup_desc)
                },
            ),
        )
        channelsReady = true
    }

    private companion object {
        const val CHANNEL_DEFAULT = "maa_run_events_low"
        const val CHANNEL_HIGH = "maa_run_events_high"

        /** 整轮结局共用一个 id：后一轮的结果顶掉上一轮的，通知栏里只留最新那条 */
        const val ID_RUN_RESULT = 9001
    }
}
