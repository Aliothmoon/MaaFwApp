package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.RemoteService
import kotlinx.coroutines.flow.StateFlow

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

    /** 已连接时给出服务面，否则 null；不触发绑定 */
    fun serviceOrNull(): RemoteService?

    /** 先刷新授权、必要时发起授权与重绑，再把服务面交给 [action] */
    suspend fun <R> useService(action: suspend (RemoteService) -> R): R
}
