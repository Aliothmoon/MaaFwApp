package com.aliothmoon.maafw.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 「通知能不能弹」的视图：
 *
 * - [isGranted] 在 ViewModel 里同步判一下，决定要不要把请求抛给 UI 层
 * - 真正的弹窗（系统运行时对话框 / 通知详情页）由 UI 层调 [com.aliothmoon.maafw.privileged.SystemPermissionRequester]
 *
 * 抽出来是因为 [SettingsViewModel] 已经在 `viewModelModule` 里看不到 Activity，
 * 这一层只能拿 `Context`；拿不到就直接走 [NotificationManagerCompat.areNotificationsEnabled]，
 * 跳过 [SettingsEffect.RequestNotificationPermission] 那条 effect，让 UI 自己再决定要不要走
 * `ACTION_APP_NOTIFICATION_SETTINGS` 跳转兜底
 */
interface NotificationPermissionRequester {
    /** 当前进程是否能在通知栏里展示（已授权且全局通知开关未关） */
    fun isGranted(): Boolean
}

class DefaultNotificationPermissionRequester(
    private val appContext: Context,
) : NotificationPermissionRequester {

    override fun isGranted(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()
}
