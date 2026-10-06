package com.aliothmoon.maafw.remote.internal

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

/**
 * 后台虚拟屏上的目标游戏实时帧率
 *
 * Android 13+ 优先用 SurfaceFlinger 的 task present-to-present FPS；旧系统或注册失败时，
 * 按 libbridge 收到的合成帧计数做差。虚拟屏预览上只有目标游戏，两种口径都可用。
 */
object GameFpsMonitor {

    private const val TAG = "GameFpsMonitor"
    const val UNKNOWN = -1f

    /** SurfaceFlinger 只在合成时派发；静止画面超过该时长视为 0 FPS */
    private const val TASK_FPS_STALE_MS = 2_000L
    private const val FRAME_COUNT_INTERVAL_MS = 1_000L

    @Volatile
    private var source: Source? = null

    @JvmStatic
    @Synchronized
    fun start(packageName: String) {
        stop()
        val next = createTaskSource(packageName)?.takeIf { it.start() }
            ?: FrameCountSource().also { it.start() }
        source = next
        Ln.i("$TAG: start ${next.name} for $packageName")
    }

    /** 游戏不是由框架拉起时，看门狗确认它在虚拟屏上后从这里补启动 */
    @JvmStatic
    @Synchronized
    fun ensureStarted(packageName: String) {
        if (source == null) start(packageName)
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        source?.let {
            it.stop()
            Ln.i("$TAG: stop ${it.name}")
        }
        source = null
    }

    @JvmStatic
    fun currentFps(): Float = source?.currentFps() ?: UNKNOWN

    private fun createTaskSource(packageName: String): TaskFpsSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val taskId = ActivityUtils.findTaskId(packageName) ?: run {
            Ln.w("$TAG: no task for $packageName, fall back to frame count")
            return null
        }
        return TaskFpsSource(taskId)
    }

    private interface Source {
        val name: String

        /** 失败返回 false，调用方换下一种来源 */
        fun start(): Boolean
        fun stop()
        fun currentFps(): Float
    }

    /** ITaskFpsCallback 只有一个 oneway onFpsReported(float)，手工 Binder 比引入隐藏类副本轻 */
    private class TaskFpsSource(private val taskId: Int) : Source {

        override val name = "TaskFps(task=$taskId)"

        @Volatile
        private var fps = 0f

        @Volatile
        private var lastReportMs = 0L

        private val binder = object : Binder() {
            init {
                attachInterface(null, DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TRANSACTION_ON_FPS_REPORTED) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DESCRIPTOR)
                fps = data.readFloat()
                lastReportMs = SystemClock.elapsedRealtime()
                return true
            }
        }

        private val callback: Any? = runCatching {
            val iface = Class.forName("android.window.ITaskFpsCallback")
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    "toString" -> name
                    else -> null
                }
            }
        }.getOrNull()

        override fun start(): Boolean {
            val cb = callback ?: return false
            val ok = ServiceManager.getWindowManager().registerTaskFpsCallback(taskId, cb)
            if (ok) lastReportMs = SystemClock.elapsedRealtime()
            else Ln.w("$TAG: registerTaskFpsCallback failed, fall back to frame count")
            return ok
        }

        override fun stop() {
            callback?.let { ServiceManager.getWindowManager().unregisterTaskFpsCallback(it) }
        }

        override fun currentFps(): Float =
            if (SystemClock.elapsedRealtime() - lastReportMs > TASK_FPS_STALE_MS) 0f else fps

        private companion object {
            const val DESCRIPTOR = "android.window.ITaskFpsCallback"
            const val TRANSACTION_ON_FPS_REPORTED = IBinder.FIRST_CALL_TRANSACTION
        }
    }

    private class FrameCountSource : Source {

        override val name = "FrameCount"

        @Volatile
        private var fps = 0f

        @Volatile
        private var running = false
        private var worker: Thread? = null

        override fun start(): Boolean {
            running = true
            worker = thread(name = "game-fps-sampler", isDaemon = true) {
                val estimator = FrameCountFpsEstimator()
                estimator.sample(NativeBridgeLib.getFrameCount(), System.nanoTime())
                while (running) {
                    try {
                        Thread.sleep(FRAME_COUNT_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    estimator.sample(NativeBridgeLib.getFrameCount(), System.nanoTime())?.let { fps = it }
                }
            }
            return true
        }

        override fun stop() {
            running = false
            worker?.interrupt()
            worker = null
        }

        override fun currentFps(): Float = fps
    }
}
