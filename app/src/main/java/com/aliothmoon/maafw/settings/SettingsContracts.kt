package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.privileged.RemoteAccessState

/**
 * 设置页聚合态
 *
 * 目前只承载后端选择；Shizuku 子设置（skip/shortcut/launchPackage）与运行模式那簇
 * 后续迁进来时再扩字段。后端的写走 PermissionGateway.setBackend（带 unbind 副作用），
 * 不直接落 AppSettings——跳过 unbind 会连着错的特权进程
 */
data class SettingsUiState(
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
)

sealed interface SettingsIntent {
    /** 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程 */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent
}
