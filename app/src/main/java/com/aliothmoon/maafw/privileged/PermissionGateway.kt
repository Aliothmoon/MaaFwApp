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
    val serviceState: StateFlow<PrivilegedServiceState>

    /** 保活相关的两项系统权限，非提权后端 */
    val systemPermissions: StateFlow<SystemPermissionState>

    suspend fun requestRemoteAccess(): Boolean
    suspend fun setBackend(backend: RemoteBackend)
    suspend fun skipShizukuCheck()
    fun refresh()

    /** 手动拉起特权进程；缺授权时顺带发起一次授权 */
    suspend fun bindService(): ServiceBindResult
    fun unbindService()
}

/**
 * 特权进程连接态的对外投影
 *
 * 不直接暴露 `RemoteServiceManager.ServiceState`：那个 sealed class 的 Connected 带着
 * `RemoteService` binder，漏进 UiState 就等于让 UI 拿到了 IPC 句柄
 *
 * [Died] 与 [Disconnected] 必须分开：前者是特权进程崩了或被 ROM 杀了，后者是还没连过，
 * 合成一个 Boolean 之后界面上这两种情况长得一模一样
 */
enum class PrivilegedServiceState {
    Disconnected,
    Connecting,
    Connected,
    Died,
    Error,
}

/** [PermissionGateway.bindService] 的结局；文案由 UI 层挑，这里只给分类 */
sealed interface ServiceBindResult {
    /** 绑定已发起——连上是异步的，看 [PermissionGateway.serviceState] */
    data object Started : ServiceBindResult
    data object AlreadyConnected : ServiceBindResult
    data class BackendUnavailable(val backend: RemoteBackend) : ServiceBindResult
    data class AuthRejected(val backend: RemoteBackend) : ServiceBindResult
    data class Failed(val reason: String) : ServiceBindResult
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
