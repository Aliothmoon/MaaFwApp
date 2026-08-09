package com.aliothmoon.maafw.runner

import android.view.Surface
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * 虚拟屏预览的接线口：UI 交出 SurfaceView 的 Surface，由实现方递给特权进程
 * UI 不直接持有 RemoteService（docs/android-ui-contract.md）
 */
interface PreviewPort {
    fun attachSurface(surface: Surface)
    fun detachSurface()
}

/**
 * Surface 通常在 Start 之前就绪，那时特权进程还没绑定，因此这里缓存最后一个 Surface，
 * 每次连上（含 binder 死后重连）都补发一次；不补发的话预览会一直是黑的
 */
class RemotePreviewPort(
    scope: CoroutineScope,
    private val serviceManager: RemoteServiceManager,
) : PreviewPort {

    private val current = AtomicReference<Surface?>(null)

    init {
        scope.launch {
            serviceManager.state.collect { state ->
                if (state is RemoteServiceManager.ServiceState.Connected) {
                    push(current.get())
                }
            }
        }
    }

    override fun attachSurface(surface: Surface) {
        current.set(surface)
        push(surface)
    }

    override fun detachSurface() {
        current.set(null)
        push(null)
    }

    /** 未绑定时静默跳过：连上时 init 里的 collect 会补发 */
    private fun push(surface: Surface?) {
        val service = serviceManager.getInstanceOrNull() ?: return
        runCatching { service.setMonitorSurface(surface) }
            .onFailure { Timber.w(it, "setMonitorSurface failed") }
    }
}
