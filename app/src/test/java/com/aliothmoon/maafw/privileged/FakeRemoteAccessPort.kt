package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRemoteAccessPort(initial: RemoteAccessState = RemoteAccessState()) : RemoteAccessPort {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<RemoteAccessState> = _state.asStateFlow()

    /** 依次交给后续的 refresh，用完停在最后一个；模拟冷进程里 binder / 授权晚到 */
    val upcoming = ArrayDeque<RemoteAccessState>()

    val requested = mutableListOf<RemoteBackend>()

    override fun refresh(): RemoteAccessState {
        upcoming.removeFirstOrNull()?.let { _state.value = it }
        return _state.value
    }

    override suspend fun request(backend: RemoteBackend): Boolean {
        requested += backend
        return _state.value.isGranted(backend)
    }
}
