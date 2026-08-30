package com.aliothmoon.maafw.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 「通知能不能弹」的视图：[SettingsViewModel] 在启动下载前同步判一下，
 * 拒了就把请求抛给 UI 层弹系统运行时框（SettingsEffect.RequestNotificationPermission）
 */
class NotificationPermissionRequester(
    private val appContext: Context,
) {

    /** 当前进程是否能在通知栏上展示（已授权且全局通知开关未关） */
    fun isGranted(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()
}
