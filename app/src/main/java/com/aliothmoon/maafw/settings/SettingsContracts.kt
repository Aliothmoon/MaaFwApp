package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateSource

/**
 * 设置页聚合态
 *
 * 目前只承载后端选择；Shizuku 子设置（skip/shortcut/launchPackage）与运行模式那簇
 * 后续迁进来时再扩字段。后端的写走 PermissionGateway.setBackend（带 unbind 副作用），
 * 不直接落 AppSettings——跳过 unbind 会连着错的特权进程
 */
data class SettingsUiState(
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
    val update: UpdatePanelState = UpdatePanelState(),
)

data class UpdatePanelState(
    val downloadSource: UpdateSource = UpdateSource.MIRROR_CHYAN,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val githubToken: String = "",
    val mirrorChyanCdk: String = "",
    val checking: Boolean = false,
    val checkResult: UpdateCheckResult? = null,
    val downloading: Boolean = false,
    val downloadedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val downloadedVersion: String? = null,
    val installerStarted: Boolean = false,
    val errorMessage: String? = null,
) {
    val availableUpdate: UpdateCheckResult.UpdateAvailable?
        get() = checkResult as? UpdateCheckResult.UpdateAvailable

    val credentialMissing: Boolean
        get() = downloadSource == UpdateSource.MIRROR_CHYAN && mirrorChyanCdk.isBlank()

    val canDownload: Boolean
        get() = availableUpdate != null && !checking && !downloading && !credentialMissing
}

sealed interface SettingsIntent {
    /** 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程 */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent

    data class SetUpdateDownloadSource(val source: UpdateSource) : SettingsIntent
    data class SetUpdateChannel(val channel: UpdateChannel) : SettingsIntent
    data class SetGithubToken(val token: String) : SettingsIntent
    data class SetMirrorChyanCdk(val cdk: String) : SettingsIntent
    data object CheckUpdate : SettingsIntent
    data object DownloadUpdate : SettingsIntent
}
