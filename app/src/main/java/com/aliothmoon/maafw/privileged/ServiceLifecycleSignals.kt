package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 最近一次已连接提权进程的死亡事实；StateFlow 让遥测晚于服务初始化时也能补处理 */
data class ServiceProcessDeath(
    val pid: Int?,
    val backend: RemoteBackend?,
    val diedAtMs: Long,
)

object ServiceLifecycleSignals {
    private val _lastDeath = MutableStateFlow<ServiceProcessDeath?>(null)
    val lastDeath: StateFlow<ServiceProcessDeath?> = _lastDeath.asStateFlow()

    fun recordDeath(death: ServiceProcessDeath) {
        _lastDeath.value = death
    }
}
