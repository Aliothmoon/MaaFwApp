package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.RemoteAccessState
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
    val pipOnHome: Boolean = true,
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
     * 非空即弹错误 dialog，与 [updatePrompt] 互斥——错误和发现新版本走同一种呈现，不做内联红字；
     * 标题随阶段区分：检查失败「检查更新失败」，点了下载之后的失败「更新失败」。
     * 启动自检的错误同样写入；CDK 填写触发的静默检查不写
     */
    val errorPrompt: UpdateErrorPrompt? = null,
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
    data class SetPipOnHome(val enabled: Boolean) : SettingsIntent
    data object CheckUpdate : SettingsIntent
    data object DownloadUpdate : SettingsIntent
    data object CancelDownload : SettingsIntent
    data object DismissUpdatePrompt : SettingsIntent
    data object DismissUpdateError : SettingsIntent
}

/**
 * 更新链路错误弹窗的载荷；两个工厂定标题，写入方不用各自从 strings 拼一遍
 */
data class UpdateErrorPrompt(
    val title: UiText,
    val message: UiText,
) {
    companion object {
        /** 检查阶段失败（含 CDK 业务错误） */
        fun check(message: UiText) =
            UpdateErrorPrompt(uiTextOf(R.string.dialog_update_error_title), message)

        /** 点了「下载更新」之后的失败：CDK 前置拦截与地址解析 */
        fun download(message: UiText) =
            UpdateErrorPrompt(uiTextOf(R.string.dialog_update_failed_title), message)
    }
}
