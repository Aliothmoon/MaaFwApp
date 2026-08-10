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

    /** 保活相关的两项系统权限，非提权后端 */
    val systemPermissions: StateFlow<SystemPermissionState>

    suspend fun requestRemoteAccess(): Boolean
    suspend fun setBackend(backend: RemoteBackend)
    suspend fun skipShizukuCheck()
    fun refresh()
}

/**
 * 后台跑任务的保活前提
 *
 * app 进程一死，特权进程的看门狗就自杀并释放虚拟屏——实测 MIUI 的 SwipeUpClean
 * 会按 Adj=905 直接 force-stop。前台服务本体还没做，这两项先让用户能自己开
 */
data class SystemPermissionState(
    val notification: Boolean = false,
    val batteryWhitelist: Boolean = false,
)
