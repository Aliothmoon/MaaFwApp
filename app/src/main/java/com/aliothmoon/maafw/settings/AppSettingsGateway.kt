package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunMode
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 侧看得见的那部分 app 设置；实现是 [AppSettingsManager]
 * 与 [com.aliothmoon.maafw.privileged.PermissionGateway] 同一路子：让 VM 测试能塞 fake，
 * 而不必把 DataStore 一起拖进来
 */
interface AppSettingsGateway {
    val runMode: StateFlow<RunMode>
    suspend fun setRunMode(mode: RunMode)

    val overlayControlMode: StateFlow<OverlayControlMode>
    suspend fun setOverlayControlMode(mode: OverlayControlMode)
}
