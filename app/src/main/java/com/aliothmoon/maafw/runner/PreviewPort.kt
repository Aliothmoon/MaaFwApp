package com.aliothmoon.maafw.runner

import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 预览上的一次触点，坐标在虚拟屏坐标系；[action] 是 MotionEvent 的 actionMasked */
data class PreviewTouchMarker(
    val id: Long,
    val x: Int,
    val y: Int,
    val action: Int,
    val createdAtMs: Long,
) {
    companion object {
        const val MAX_ACTIVE_MARKERS = 8
        const val TTL_MS = 600L
        const val CLEANUP_INTERVAL_MS = 100L
    }
}

/**
 * 虚拟屏预览的接线口：UI 交出 SurfaceView 的 Surface，由实现方递给特权进程
 * UI 不直接持有 RemoteService（docs/android-ui-contract.md）
 */
interface PreviewPort {
    /** 注入到虚拟屏的触点，供预览叠加显示；纯视觉信号，不进 SessionUiState */
    val markers: StateFlow<List<PreviewTouchMarker>>

    fun attachSurface(surface: Surface)
    fun detachSurface()

    /**
     * 用户在预览上的手动操作，坐标已换算到虚拟屏坐标系
     * 与 MaaFramework 注入的是同一条 InputControlUtils 通路，会走同一份触点回调
     */
    fun touchDown(x: Int, y: Int)
    fun touchMove(x: Int, y: Int)
    fun touchUp(x: Int, y: Int)
}

/**
 * Surface 通常在 Start 之前就绪，那时特权进程还没绑定，因此这里缓存最后一个 Surface，
 * 每次连上（含 binder 死后重连）都补发一次；不补发的话预览会一直是黑的
 *
 * 触点回调与 Surface 同生共死：没有预览面时特权进程不必跨进程发这些事件
 */
class RemotePreviewPort(
    private val scope: CoroutineScope,
    private val serviceManager: RemoteServiceManager,
) : PreviewPort {

    private val current = AtomicReference<Surface?>(null)
    private val markerId = AtomicLong(0L)
    private var cleanupJob: Job? = null

    private val _markers = MutableStateFlow<List<PreviewTouchMarker>>(emptyList())
    override val markers: StateFlow<List<PreviewTouchMarker>> = _markers.asStateFlow()

    private val touchCallback = object : ITouchEventCallback.Stub() {
        override fun onCallback(x: Int, y: Int, type: Int) {
            if (type != MotionEvent.ACTION_DOWN &&
                type != MotionEvent.ACTION_MOVE &&
                type != MotionEvent.ACTION_UP
            ) {
                return
            }
            appendMarker(PreviewTouchMarker(markerId.incrementAndGet(), x, y, type, SystemClock.elapsedRealtime()))
        }
    }

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
        cleanupJob?.cancel()
        cleanupJob = null
        _markers.value = emptyList()
    }

    override fun touchDown(x: Int, y: Int) = withService { it.touchDown(x, y) }

    override fun touchMove(x: Int, y: Int) = withService { it.touchMove(x, y) }

    override fun touchUp(x: Int, y: Int) = withService { it.touchUp(x, y) }

    /** 手动触摸是 oneway，发不出去就算了；预览本来就是尽力而为 */
    private inline fun withService(action: (RemoteService) -> Unit) {
        val service = serviceManager.getInstanceOrNull() ?: return
        runCatching { action(service) }.onFailure { Timber.w(it, "预览手动触摸失败") }
    }

    /** 未绑定时静默跳过：连上时 init 里的 collect 会补发 */
    private fun push(surface: Surface?) {
        val service = serviceManager.getInstanceOrNull() ?: return
        runCatching {
            service.setMonitorSurface(surface)
            service.setTouchCallback(if (surface != null) touchCallback else null)
        }.onFailure { Timber.w(it, "setMonitorSurface failed") }
    }

    /** 只留最近 [PreviewTouchMarker.MAX_ACTIVE_MARKERS] 个：滑动会连发几十个触点 */
    private fun appendMarker(marker: PreviewTouchMarker) {
        _markers.update { current ->
            val start = (current.size - PreviewTouchMarker.MAX_ACTIVE_MARKERS + 1).coerceAtLeast(0)
            current.subList(start, current.size) + marker
        }
        ensureCleanupJob()
    }

    /** 过期标记靠这个循环清；清空即退出，不常驻 */
    private fun ensureCleanupJob() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (true) {
                delay(PreviewTouchMarker.CLEANUP_INTERVAL_MS)
                val cutoff = SystemClock.elapsedRealtime() - PreviewTouchMarker.TTL_MS
                _markers.update { list -> list.filter { it.createdAtMs > cutoff } }
                if (_markers.value.isEmpty()) break
            }
        }
    }
}
