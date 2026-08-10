package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 看到的提权面
 *
 * 抽接口只为可测：单测里换成记录调用的替身，不去碰 Shizuku binder 与 ProcessLifecycleOwner。
 * 生产实现是 [PermissionManager]，由 Koin 显式注入，不做默认参数
 */
interface PermissionGateway {
    val state: StateFlow<RemoteAccessState>
    val isGranting: StateFlow<Boolean>
    val readiness: StateFlow<ShizukuReadiness>
    val serviceConnected: StateFlow<Boolean>

    suspend fun requestRemoteAccess(): Boolean
    suspend fun setBackend(backend: RemoteBackend)
    suspend fun skipShizukuCheck()
}
