package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 汇总两个后端的可用性与授权状态
 * 后端选择由外部提供：本层不决定它存在哪，也不感知持久化
 */
object RemoteAccessCoordinator : RemoteAccessPort {

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = RemoteAccessStateListener { refresh() }
    private val _state = MutableStateFlow(snapshot())
    @Volatile
    private var backendProvider: (() -> RemoteBackend)? = null

    override val state: StateFlow<RemoteAccessState> = _state.asStateFlow()

    private val backends = mapOf(
        RemoteBackend.ROOT to RootManager,
        RemoteBackend.SHIZUKU to ShizukuManager
    )

    fun initialize(backendProvider: () -> RemoteBackend) {
        this.backendProvider = backendProvider
        if (initialized.compareAndSet(false, true)) {
            backends.values.forEach { it.addStateListener(listener) }
            
            scope.launch {
                val backend = configuredBackend()
                if (backend == RemoteBackend.ROOT) {
                    backends.getValue(RemoteBackend.ROOT).requestPermission()
                }
            }
        }
        refresh()
    }

    override fun refresh(): RemoteAccessState {
        val current = snapshot()
        _state.value = current
        return current
    }

    fun isAvailable(backend: RemoteBackend): Boolean {
        return state.value.isAvailable(backend)
    }

    fun isGranted(backend: RemoteBackend): Boolean {
        return state.value.isGranted(backend)
    }

    override suspend fun request(backend: RemoteBackend): Boolean {
        val current = refresh()
        if (current.isGranted(backend)) {
            return true
        }
        if (!current.isAvailable(backend)) {
            return false
        }
        val granted = backends.getValue(backend).requestPermission()
        val refreshed = refresh()
        return granted && refreshed.isGranted(backend)
    }

    fun configuredBackend(): RemoteBackend {
        return backendProvider?.invoke() ?: RemoteBackend.SHIZUKU
    }

    private fun snapshot(): RemoteAccessState {
        val shizukuAvailable = ShizukuManager.isAvailable()
        val shizukuGranted = ShizukuManager.isGranted()
        val rootAvailable = RootManager.isAvailable()
        val rootGranted = RootManager.isGranted()
        return RemoteAccessState(
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            rootAvailable = rootAvailable,
            rootGranted = rootGranted,
            configuredBackend = configuredBackend()
        )
    }
}
