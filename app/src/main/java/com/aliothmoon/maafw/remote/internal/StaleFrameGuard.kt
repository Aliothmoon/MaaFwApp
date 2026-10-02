package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.remote.internal.ActivityUtils.DisplayOccupancy
import com.aliothmoon.maafw.remote.internal.StaleFrameDetector.Observation
import com.aliothmoon.maafw.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 帧缓冲只在虚拟屏有新合成时才更新：目标应用退出、被杀或窗口飘走后没人再画，
 * 截图就一直是它的最后一帧，识别照常命中、点击全落空，任务对着残影空转不超时
 *
 * 这里把残影换成黑帧（与 InitFrameBuffers 的种子帧同一种），截图照常成功，只是什么都认不出，
 * 走回 pipeline 自己的 timeout / on_error
 *
 * 只管后台虚拟屏：主屏上应用退了桌面会接着出帧
 */
object StaleFrameGuard {

    private const val TICK_MS = 500L

    /** pidof 要 fork 一个进程，而静止界面会一直停在「帧停了、屏上有任务」这一支上 */
    private const val LIVENESS_INTERVAL_MS = 1_000L

    private const val ANY_FRAME_COUNT = -1L

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO.limitedParallelism(1))

    private var job: Job? = null

    @Synchronized
    fun start() {
        job?.cancel()
        job = scope.launch {
            val detector = StaleFrameDetector()
            var livenessCheckedMs = 0L
            while (isActive) {
                delay(TICK_MS)
                val displayId = VirtualDisplayManager.getDisplayId()
                if (displayId == VirtualDisplayManager.DISPLAY_NONE) continue
                // 还在出帧就不用查：游戏运行期间这里不发任何 binder 调用
                val frameCount = NativeBridgeLib.getFrameCount()
                if (!detector.stalled(frameCount)) continue

                val observation = when (val occupancy = ActivityUtils.probeDisplay(displayId)) {
                    DisplayOccupancy.Empty -> Observation.GONE
                    DisplayOccupancy.Unknown -> Observation.UNKNOWN
                    is DisplayOccupancy.Occupied -> {
                        // 任务还挂在屏上但进程没了：被 kill 的应用不会来收自己的窗口
                        val pkg = occupancy.topPackage
                        val now = SystemClock.elapsedRealtime()
                        when {
                            pkg == null -> Observation.PRESENT
                            now - livenessCheckedMs < LIVENESS_INTERVAL_MS -> continue
                            else -> {
                                livenessCheckedMs = now
                                when (ProcessLiveness.of(pkg)) {
                                    ProcessLiveness.DEAD -> Observation.GONE
                                    ProcessLiveness.ALIVE -> Observation.PRESENT
                                    ProcessLiveness.UNKNOWN -> Observation.UNKNOWN
                                }
                            }
                        }
                    }
                }
                // 带上判定时的帧计数：期间来过新帧就说明又有人在画，native 侧不会换
                if (detector.confirm(observation) && NativeBridgeLib.invalidateFrame(frameCount)) {
                    Ln.w("StaleFrameGuard: nothing draws display $displayId anymore, frame buffer blanked")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 刚强杀完 [displayId] 上的应用时调：不等轮询，下一次截图就不该再是它的画面
     *
     * 屏上还有别的任务时不动——它会自己出帧，抢在它后面作废反而把它那一帧盖掉。
     * 判不出时照样作废：应用是我们自己刚杀的
     */
    @JvmStatic
    fun onAppKilled(displayId: Int) {
        if (displayId == VirtualDisplayManager.DISPLAY_NONE) return
        if (displayId != VirtualDisplayManager.getDisplayId()) return
        if (ActivityUtils.probeDisplay(displayId) is DisplayOccupancy.Occupied) return
        if (NativeBridgeLib.invalidateFrame(ANY_FRAME_COUNT)) {
            Ln.i("StaleFrameGuard: app on display $displayId was force-stopped, frame buffer blanked")
        }
    }
}
