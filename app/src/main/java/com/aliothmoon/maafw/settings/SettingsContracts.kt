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
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val updateSource: UpdateSource = UpdateSource.MIRRORCHYAN,
    val mirrorchyanCdk: String = "",
    val autoCheckUpdate: Boolean = true,
    val autoDownloadUpdate: Boolean = false,
    val checking: Boolean = false,
    val checkResult: UpdateCheckResult? = null,
    /**
     * 非空即弹「发现新版本」dialog（AppRoot 渲染，任意 tab 可见）；
     * 启动自检与手动检查发现新版本都写入，开始下载或用户忽略时清空
     */
    val updatePrompt: UpdateCheckResult.UpdateAvailable? = null,
    /**
     * 非空即弹「检查更新失败」dialog，与 [updatePrompt] 互斥——检查错误（含 CDK 业务错误）
     * 和发现新版本走同一种呈现，不做内联红字。启动自检的错误同样写入；
     * CDK 填写触发的静默检查不写
     */
    val errorPrompt: UiText? = null,
    val downloading: Boolean = false,
    val downloadedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val errorMessage: UiText? = null,
) {
    val availableUpdate: UpdateCheckResult.UpdateAvailable?
        get() = checkResult as? UpdateCheckResult.UpdateAvailable
}

sealed interface SettingsIntent {
    /** 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程 */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent

    data class SetUpdateChannel(val channel: UpdateChannel) : SettingsIntent
    /** 切换更新源；检查与下载都只走它 */
    data class SetUpdateSource(val source: UpdateSource) : SettingsIntent
    /** 只在 Mirror酱 源时有意义；空值在下载前置拦截（CDK_REQUIRED） */
    data class SetMirrorchyanCdk(val cdk: String) : SettingsIntent
    data class SetAutoCheckUpdate(val enabled: Boolean) : SettingsIntent
    data class SetAutoDownloadUpdate(val enabled: Boolean) : SettingsIntent
    data object CheckUpdate : SettingsIntent
    data object DownloadUpdate : SettingsIntent
    data object CancelDownload : SettingsIntent
    data object DismissUpdatePrompt : SettingsIntent
    data object DismissUpdateError : SettingsIntent
}
