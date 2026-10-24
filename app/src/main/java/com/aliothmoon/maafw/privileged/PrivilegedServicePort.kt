package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration

/**
 * 特权进程的连接边界
 *
 * 与 [RemoteAccessPort] 分工：那边管「有没有权限」，这边管「连没连上」——
 * 两者的失败原因与恢复手段都不一样，合成一个接口之后调用方分不清该重授还是该重连
 *
 * 生产实现是 [RemoteServiceManager]（它持 binder 与 linkToDeath，本就该是单例），
 * 由 Koin 显式注入，不做默认参数：默认值挂生产实现会让 RunnerPort 与 PreviewPort
 * 看着可替换其实不可能替换
 */
interface PrivilegedServicePort {

    /** 连接态；binder 不出这一层（见 [PrivilegedServiceState]） */
    val serviceState: StateFlow<PrivilegedServiceState>

    /**
     * 当前 UI 正在操作的路号
     *
     * 单路实现恒为 0；多路门面按它分发上面这些成员，UI 与执行器也按它区分各自的进度
     */
    val activeSlot: StateFlow<Int>

    fun bind()
    fun unbind()

    /** 当前绑定用的后端；LogcatService 要跟主服务同后端、同拉起方式，未绑定时为 null */
    val currentBackend: RemoteBackend?

    /** 已连接时给出服务面，否则 null；不触发绑定 */
    fun serviceOrNull(): RemoteService?

    /** 按路取服务面；不触发绑定。默认退化为当前路，单路实现不必重写 */
    fun serviceOrNull(slot: Int): RemoteService? = serviceOrNull()

    /** 先刷新授权、必要时发起授权与重绑，再把服务面交给 [action] */
    suspend fun <R> useService(action: suspend (RemoteService) -> R): R

    /**
     * 按路把服务面交给 [action]：多开启动某一路时用，确保授权刷新/重绑/取值都钉在
     * 该路的连接上，不被随后切路带偏。默认退化为当前路，单路实现不必重写。
     */
    suspend fun <R> useService(slot: Int, action: suspend (RemoteService) -> R): R = useService(action)
}

suspend fun <T : Any> PrivilegedServicePort.callWithTimeout(timeout: Duration, block: (RemoteService) -> T): T? {
    val service = serviceOrNull() ?: return null
    val call = CoroutineScope(MaaDispatchers.IO).async {
        runCatching { block(service) }
            .onFailure { Timber.w(it, "privileged call failed") }
            .getOrNull()
    }
    return withTimeoutOrNull(timeout) { call.await() }
}
