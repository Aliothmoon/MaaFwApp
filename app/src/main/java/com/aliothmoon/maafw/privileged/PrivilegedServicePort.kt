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

    fun bind()
    fun unbind()

    /** 当前绑定用的后端；LogcatService 要跟主服务同后端、同拉起方式，未绑定时为 null */
    val currentBackend: RemoteBackend?

    /** 已连接时给出服务面，否则 null；不触发绑定 */
    fun serviceOrNull(): RemoteService?

    /**
     * 先刷新授权、必要时发起授权与重绑，再把服务面交给 [action]
     *
     * 后端没就绪抛 [RemoteAccessException]，连不上抛连接器给的原因
     */
    suspend fun <R> useService(action: suspend (RemoteService) -> R): R

    /** 与 [useService] 同一条连接路径，可预期的失败交回来而不抛；调用方自己被取消照常抛 */
    suspend fun connect(): ServiceConnectResult

    /**
     * 限时调一次 [service]：超时先取线程栈，再重启特权进程把卡住的线程放出来，抛 [RemoteCallStuckException]
     *
     * 特权侧的显示方法共用一把锁，一个卡住后面全排队，只放弃等待的话下一轮照样卡在同一处。
     * binder 阻塞取消不掉，调用方中途被取消，判卡与重启照样做完
     */
    suspend fun <R> callOrRestart(service: RemoteService, call: String, timeoutMs: Long, block: (RemoteService) -> R): R
}

/** 显示调用正常是毫秒级，15s 没回就是卡死 */
const val DISPLAY_CALL_TIMEOUT_MS = 15_000L

/** 建屏要逐档回退，比别的显示调用慢 */
const val START_DISPLAY_TIMEOUT_MS = 30_000L

enum class RemoteAccessFailure {
    /** Shizuku 没在跑 / 没有 su */
    UNAVAILABLE,
    NOT_GRANTED,
}

/** 提权后端没就绪；文案按 [failure] 走 [remoteAccessMessage]，别拿 message 当文案 */
class RemoteAccessException(
    val backend: RemoteBackend,
    val failure: RemoteAccessFailure,
) : IllegalStateException("$backend ${failure.name.lowercase()}")

/** [call] 限时内没回，特权进程已重启；[threadDump] 已写进 service_boot_debug.log */
class RemoteCallStuckException(val call: String, val threadDump: String?) :
    Exception("$call did not return, privileged process restarted")

sealed interface ServiceConnectResult {
    data object Connected : ServiceConnectResult

    data class NotReady(val backend: RemoteBackend, val failure: RemoteAccessFailure) : ServiceConnectResult

    /** 授权齐了但进程没拉起来或没回 binder；[error] 的 message 带着连接器归因，只能作细节后缀 */
    data class Failed(val backend: RemoteBackend, val error: Throwable) : ServiceConnectResult
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
