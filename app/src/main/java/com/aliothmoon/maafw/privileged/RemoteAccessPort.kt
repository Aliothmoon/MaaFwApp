package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.StateFlow

/**
 * 两个提权后端的可用性与授权边界
 *
 * 后端选择不在这里：本层不决定它存在哪，也不感知持久化（见 [RemoteAccessCoordinator]）
 * 生产实现是 [RemoteAccessCoordinator]，由 Koin 显式注入，不做默认参数
 */
interface RemoteAccessPort {
    val state: StateFlow<RemoteAccessState>

    /** 重读两个后端的可用性与授权，顺带返回新快照 */
    fun refresh(): RemoteAccessState

    /** 向指定后端发起授权；已授权直接返回 true，后端不可用返回 false */
    suspend fun request(backend: RemoteBackend): Boolean
}
