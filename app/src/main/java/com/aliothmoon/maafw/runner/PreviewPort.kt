package com.aliothmoon.maafw.runner

import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
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

/** 与特权侧 `TouchPointerSequence.MAX_CONTACTS` 同值；校验在那边做，这里只是不把依赖伸进 bridge */
const val MAX_PREVIEW_CONTACTS = 16

/**
 * 预览上的一次触点，坐标在虚拟屏坐标系
 *
 * [action] 只取 DOWN / MOVE / UP：POINTER_DOWN / POINTER_UP 折进前两者，第几根手指看 [contact]
 */
data class PreviewTouchMarker(
    val id: Long,
    val x: Int,
    val y: Int,
    val action: Int,
    val contact: Int,
    val createdAtMs: Long,
) {
    companion object {
        /** 双指时每根手指的轨迹长度与单指持平 */
        const val MAX_ACTIVE_MARKERS = 16
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
     *
     * [contact] 是 0..15 的手指槽位，由 UI 侧按 Compose PointerId 分配
     */
    fun touchDown(x: Int, y: Int, contact: Int)
    fun touchMove(x: Int, y: Int, contact: Int)
    fun touchUp(x: Int, y: Int, contact: Int)
}

/**
 * Surface 通常在 Start 之前就绪，那时特权进程还没绑定，因此这里缓存最后一个 Surface，
 * 每次连上（含 binder 死后重连）都补发一次；不补发的话预览会一直是黑的
 *
 * 触点回调与 Surface 同生共死：没有预览面时特权进程不必跨进程发这些事件。
 * [touchPreviewEnabled] 关着时同样不注册——一次滑动能连发几十条 oneway 调用，
 * 用户不看触点就不该让它们跨进程
 */
class RemotePreviewPort(
    private val scope: CoroutineScope,
    private val servicePort: PrivilegedServicePort,
    private val touchPreviewEnabled: StateFlow<Boolean>,
) : PreviewPort {

    private val current = AtomicReference<Surface?>(null)
    private val markerId = AtomicLong(0L)
    private var cleanupJob: Job? = null

    private val _markers = MutableStateFlow<List<PreviewTouchMarker>>(emptyList())
    override val markers: StateFlow<List<PreviewTouchMarker>> = _markers.asStateFlow()

    private val touchCallback = object : ITouchEventCallback.Stub() {
        override fun onCallback(x: Int, y: Int, type: Int, contact: Int) {
            val action = when (type) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> type
                MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_DOWN
                MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_UP
                else -> return
            }
            appendMarker(
                PreviewTouchMarker(
                    id = markerId.incrementAndGet(),
                    x = x,
                    y = y,
                    action = action,
                    contact = contact,
                    createdAtMs = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    init {
        scope.launch {
            servicePort.serviceState.collect { state ->
                if (state == PrivilegedServiceState.Connected) {
                    push(current.get())
                }
            }
        }
        // 开关是运行期改的，改完当场生效；关掉时顺手清空已画上去的触点，
        // 否则最后几个会在预览上停到 TTL 到期
        scope.launch {
            touchPreviewEnabled.collect { enabled ->
                pushTouchCallback()
                if (!enabled) clearMarkers()
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
        clearMarkers()
    }

    override fun touchDown(x: Int, y: Int, contact: Int) = withService { it.touchDown(x, y, contact) }

    override fun touchMove(x: Int, y: Int, contact: Int) = withService { it.touchMove(x, y, contact) }

    override fun touchUp(x: Int, y: Int, contact: Int) = withService { it.touchUp(x, y, contact) }

    /** 手动触摸是 oneway，发不出去就算了；预览本来就是尽力而为 */
    private inline fun withService(action: (RemoteService) -> Unit) {
        val service = servicePort.serviceOrNull() ?: return
        runCatching { action(service) }.onFailure { Timber.w(it, "Preview touch failed") }
    }

    /** 未绑定时静默跳过：连上时 init 里的 collect 会补发 */
    private fun push(surface: Surface?) {
        val service = servicePort.serviceOrNull() ?: return
        runCatching {
            service.setMonitorSurface(surface)
            service.setTouchCallback(callbackFor(surface))
        }.onFailure { Timber.w(it, "setMonitorSurface failed") }
    }

    /** 只动触点回调，不碰 Surface：开关切换时画面不该跟着重置 */
    private fun pushTouchCallback() {
        val service = servicePort.serviceOrNull() ?: return
        runCatching { service.setTouchCallback(callbackFor(current.get())) }
            .onFailure { Timber.w(it, "setTouchCallback failed") }
    }

    private fun callbackFor(surface: Surface?): ITouchEventCallback? =
        if (surface != null && touchPreviewEnabled.value) touchCallback else null

    private fun clearMarkers() {
        cleanupJob?.cancel()
        cleanupJob = null
        _markers.value = emptyList()
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
