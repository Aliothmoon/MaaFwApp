package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.MutableStateFlow

/** 不碰 Shizuku binder 与 ProcessLifecycleOwner，只记调用 */
class FakePermissionGateway : PermissionGateway {

    override val state = MutableStateFlow(RemoteAccessState())
    override val isGranting = MutableStateFlow(false)
    override val readiness = MutableStateFlow(ShizukuReadiness())
    override val serviceState = MutableStateFlow(PrivilegedServiceState.Disconnected)

    override val watchdogState = MutableStateFlow(WatchdogState.IDLE)
    override val systemPermissions = MutableStateFlow(SystemPermissionState())

    var requestCount: Int = 0
        private set
    var refreshCount: Int = 0
        private set
    var lastBackend: RemoteBackend? = null
        private set
    var skipCount: Int = 0
        private set

    /** 下一次 [requestRemoteAccess] 的返回值 */
    var grantResult: Boolean = true

    override suspend fun requestRemoteAccess(): Boolean {
        requestCount++
        return grantResult
    }

    override suspend fun setBackend(backend: RemoteBackend) {
        lastBackend = backend
        state.value = state.value.copy(configuredBackend = backend)
    }

    override suspend fun skipShizukuCheck() {
        skipCount++
    }

    override fun refresh() {
        refreshCount++
    }

    var bindCount: Int = 0
        private set
    var unbindCount: Int = 0
        private set

    /** 下一次 [bindService] 的返回值 */
    var bindResult: ServiceBindResult = ServiceBindResult.Started

    override suspend fun bindService(): ServiceBindResult {
        bindCount++
        return bindResult
    }

    override fun unbindService() {
        unbindCount++
    }
}
