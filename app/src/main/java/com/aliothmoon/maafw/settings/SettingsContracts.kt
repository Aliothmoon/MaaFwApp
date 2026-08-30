package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateSource

/**
 * 设置页聚合态
 *
 * 目前只承载后端选择；Shizuku 子设置（skip/shortcut/launchPackage）与运行模式那簇
 * 后续迁进来时再扩字段。后端的写走 PermissionGateway.setBackend（带 unbind 副作用），
 * 不直接落 AppSettings——跳过 unbind 会连着错特权进程
 */
data class SettingsUiState(
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
    val update: UpdatePanelState = UpdatePanelState(),
)

data class UpdatePanelState(
    val downloadSource: UpdateSource = UpdateSource.MIRRORCHYAN,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val mirrorchyanCdk: String = "",
    val checking: Boolean = false,
    val checkResult: UpdateCheckResult? = null,
    val downloading: Boolean = false,
    val downloadedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val downloadedVersion: String? = null,
    val installerStarted: Boolean = false,
    val errorMessage: UiText? = null,
    /**
     * 通知权限被拒：启动下载前先看 [com.aliothmoon.maafw.notification.NotificationPermissionRequester]，
     * 拒了就置 true + 抛 [SettingsEffect.RequestNotificationPermission] 让 UI 层弹系统对话框
     */
    val notificationPermissionDenied: Boolean = false,
) {
    val availableUpdate: UpdateCheckResult.UpdateAvailable?
        get() = checkResult as? UpdateCheckResult.UpdateAvailable

    val credentialMissing: Boolean
        get() = downloadSource == UpdateSource.MIRRORCHYAN && mirrorchyanCdk.isBlank()

    val canDownload: Boolean
        get() = availableUpdate != null && !checking && !downloading && !credentialMissing
}

sealed interface SettingsIntent {
    /** 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程 */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent

    data class SetUpdateDownloadSource(val source: UpdateSource) : SettingsIntent
    data class SetUpdateChannel(val channel: UpdateChannel) : SettingsIntent
    data class SetMirrorchyanCdk(val cdk: String) : SettingsIntent
    data object CheckUpdate : SettingsIntent
    data object DownloadUpdate : SettingsIntent

    /**
     * 系统运行时权限弹窗回调
     *
     * [granted] = true：用户允许 / 已开；继续往下走下载
     * [granted] = false：维持 [UpdatePanelState.notificationPermissionDenied] = true，UI 提示去开
     */
    data class NotificationPermissionResult(val granted: Boolean) : SettingsIntent
}

/**
 * VM → UI 的一次性指令
 *
 * - [RequestNotificationPermission]：系统运行时弹窗需要在 Activity 上发，由 UI 层
 *   拿 `Activity` 后调 [com.aliothmoon.maafw.privileged.SystemPermissionRequester.request]；
 *   弹窗结束后 UI 层用 [SettingsIntent.NotificationPermissionResult] 把结果回灌给 VM
 */
sealed interface SettingsEffect {
    data object RequestNotificationPermission : SettingsEffect
}
