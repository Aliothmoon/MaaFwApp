package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import android.view.KeyEvent
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.remote.internal.ScreenPowerAttempts.SleepAction
import com.aliothmoon.maafw.remote.internal.ScreenPowerAttempts.WakeAction
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.LOCK_SETTLE_MS
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.lockAndSleep
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.unlock
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.PowerManager
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.wrappers.WindowManager
import android.os.PowerManager as OsPowerManager

/** 唤醒/解锁/锁屏；提权进程内完成，凭证支持纯数字 PIN 与录制手势 */
object WakeUnlockController {

    private const val TAG = "WakeUnlock"

    private const val SCREEN_ON_TIMEOUT_MS = 5_000L
    private const val KEY_WAKE_TIMEOUT_MS = 2_000L
    private const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L

    /** bouncer 就绪基线，设备本就活跃时够用 */
    private const val BOUNCER_SETTLE_MS = 1_200L

    /** 限制稳定等待，避免拉长定时任务启动耗时 */
    private const val BOUNCER_SETTLE_MAX_MS = 5_000L
    private const val DIGIT_GAP_MS = 50L

    /** PIN 注入补足到固定时长，日志里的耗时推不出位数；常见位数都在预算内 */
    private const val PIN_INJECT_BUDGET_MS = 1_000L

    /** 手势回放前等锁屏首屏稳定；不弹 bouncer，比 PIN 那条路要短 */
    private const val GESTURE_SETTLE_MS = 800L

    /** 测试：上锁/息屏后等待系统稳定再解锁 */
    private const val LOCK_SETTLE_MS = 500L
    private const val SCREEN_OFF_TIMEOUT_MS = 3_000L

    /** 自测整段的 CPU 锁上限：上锁、息屏、亮屏、settle、输入、确认各步超时加起来也到不了 */
    private const val TEST_CPU_LOCK_MS = 60_000L
    private const val CPU_LOCK_TAG = "MaaFw:wake-unlock"

    /** 任务的手指可能正按着，等它抬起；槽位是进程共享的，硬塞进去会取消或并入任务的手势 */
    private const val CONTACTS_IDLE_TIMEOUT_MS = 1_000L

    /** 当前这次解锁的 trace；解锁在 binder 线程上同步跑完，按线程挂就不会和并发的另一次串 */
    private val active = ThreadLocal<WakeTrace?>()

    /** 收尾后不再改动，取时才拼文本 */
    @Volatile
    private var last: WakeTrace? = null

    /**
     * 最近一次解锁（含自测）的步骤与耗时，App 解锁失败时取走写进运行日志
     * @param sinceElapsedMs App 发起解锁时的 elapsedRealtime；更早开始的不算，免得把别次的过程记到这次头上
     */
    fun lastTrace(sinceElapsedMs: Long): String =
        last?.takeIf { it.startMs >= sinceElapsedMs }?.render().orEmpty()

    /** 嵌套调用（自测里再解锁）沿用外层那份 */
    private inline fun <T> traced(begin: String, block: () -> T): T {
        if (active.get() != null) return block()
        val trace = WakeTrace().also { last = it }
        active.set(trace)
        try {
            trace.i(begin)
            return block()
        } finally {
            active.remove()
        }
    }

    private fun logI(msg: String) {
        val trace = active.get()
        if (trace != null) trace.i(msg) else Ln.i("$TAG: $msg")
    }

    private fun logW(msg: String) {
        val trace = active.get()
        if (trace != null) trace.w(msg) else Ln.w("$TAG: $msg")
    }

    /**
     * 设置页自测：先 [lockAndSleep]，等待 [LOCK_SETTLE_MS] 后再 [unlock]
     * 整段在提权进程内完成，避免息屏后 App 侧协程被挂起
     */
    fun testUnlock(credential: String): Int = traced("test unlock, ${credentialKind(credential)}") { testUnlockTraced(credential) }

    private fun testUnlockTraced(credential: String): Int = keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return@keepCpuAwake lockCode
        logI("locked for test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        unlock(credential)
    }

    /** 设置页自测：手势版 */
    fun testUnlockGesture(gestureJson: String): Int = traced("test unlock, gesture") { testUnlockGestureTraced(gestureJson) }

    private fun testUnlockGestureTraced(gestureJson: String): Int = keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return@keepCpuAwake lockCode
        logI("locked for gesture test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        unlockWithGesture(gestureJson)
    }

    /**
     * 息屏后 CPU 一睡 Thread.sleep 就停住；自测从设置页发起，App 侧没有定时那把锁兜着，提权进程自己持一把
     * 拿不到就照旧跑，限时兜底漏放
     */
    internal inline fun <T> keepCpuAwake(timeoutMs: Long, block: () -> T): T {
        val lock = acquireCpuLock(timeoutMs)
        try {
            return block()
        } finally {
            lock?.let { if (it.isHeld) runCatching { it.release() } }
        }
    }

    internal fun acquireCpuLock(timeoutMs: Long): OsPowerManager.WakeLock? = runCatching {
        val pm = FakeContext.get().getSystemService(OsPowerManager::class.java)
            ?: return@runCatching null
        pm.newWakeLock(OsPowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }.getOrElse {
        logW("cannot hold cpu wakelock: $it")
        null
    }

    /**
     * lockNow 上锁并 goToSleep 息屏
     * @param isCancelled 录制被取消后不再息屏；lockNow 已经发出去，上锁本身收不回
     */
    fun lockAndSleep(isCancelled: () -> Boolean = { false }): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        if (!wm.lockNow()) {
            logW("lockNow unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        val keyguardUp = pollUntil(KEYGUARD_GONE_TIMEOUT_MS) {
            isCancelled() || wm.isKeyguardLocked == true
        }
        if (isCancelled()) {
            logI("cancelled after lockNow, leave screen on")
            return WakeUnlockResult.RECORD_CANCELLED
        }
        if (!keyguardUp) {
            // 锁屏方式为「无」时 lockNow 后 keyguard 永不出现；滑动/密码锁屏均会出现，
            // 超时且非 secure 即视为未设置锁屏，此时也无需息屏验证
            if (wm.isKeyguardSecure(0) != true) {
                logI("keyguard never appeared and not secure — no lock screen configured")
                return WakeUnlockResult.NO_KEYGUARD
            }
            logW("keyguard did not lock after lockNow")
            return WakeUnlockResult.LOCK_FAILED
        }

        val off = ensureScreenOff(pm, isCancelled)
        if (isCancelled()) {
            logI("cancelled while turning screen off")
            return WakeUnlockResult.RECORD_CANCELLED
        }
        if (!off) {
            logW("screen still on after sleep attempts (keyguard already locked)")
        } else {
            logI("screen locked and off")
        }
        return WakeUnlockResult.OK
    }

    internal fun bouncerSettleMs(wakeCostMs: Long): Long =
        (BOUNCER_SETTLE_MS + wakeCostMs * 2).coerceAtMost(BOUNCER_SETTLE_MAX_MS)

    /**
     * 亮屏并确认 keyguard 还在
     * @return 非 null 即调用方应当直接返回的结果码
     */
    private fun wakeAndRequireKeyguard(pm: PowerManager, wm: WindowManager): Int? {
        val startMs = SystemClock.elapsedRealtime()
        val screenWasOn = pm.isScreenOn(0)
        if (!ensureScreenOn(pm)) {
            logW("screen did not turn on after wakeUp and key fallback")
            return WakeUnlockResult.WAKE_FAILED
        }
        logI(
            "screen on in ${SystemClock.elapsedRealtime() - startMs}ms," +
                    " wasOn=$screenWasOn"
        )

        val locked = wm.isKeyguardLocked
        if (locked == null) {
            logW("isKeyguardLocked unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!locked) {
            logI("keyguard not showing, nothing to do")
            return WakeUnlockResult.OK
        }
        return null
    }

    /** 亮屏并解除锁屏；@param credential 纯数字 PIN，无凭证锁屏传空串 */
    fun unlock(credential: String): Int = traced("unlock, ${credentialKind(credential)}") { doUnlock(credential) }

    /** trace 会写进运行日志、可能随推送外发：只说有没有凭证，不带 PIN 与位数 */
    private fun credentialKind(credential: String): String = if (credential.isEmpty()) "no credential" else "pin"

    private fun doUnlock(credential: String): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        val wakeStartMs = SystemClock.elapsedRealtime()
        wakeAndRequireKeyguard(pm, wm)?.let { return it }
        val wakeCostMs = SystemClock.elapsedRealtime() - wakeStartMs

        val secure = wm.isKeyguardSecure(0) ?: false
        logI("keyguard locked, secure=$secure")

        if (!wm.dismissKeyguard()) {
            logW("dismissKeyguard unavailable on this ROM")
            return WakeUnlockResult.UNSUPPORTED
        }
        logI("dismissKeyguard requested")

        if (!secure) {
            return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
                logI("unlocked (insecure keyguard)")
                WakeUnlockResult.OK
            } else {
                logW("insecure keyguard did not dismiss")
                WakeUnlockResult.CREDENTIAL_REJECTED
            }
        }

        if (credential.isEmpty()) {
            logW("secure keyguard but no credential configured")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }
        if (credential.any { !it.isDigit() }) {
            logW("only numeric PIN is supported")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }

        // bouncer 弹出期间 isKeyguardLocked 仍为 true，先 settle
        val settleMs = bouncerSettleMs(wakeCostMs)
        Thread.sleep(settleMs)
        // 人脸/信任代理可能在 settle 期间解掉了锁屏，这时再按数字就落进前台应用了
        if (keyguardGone(wm)) {
            logI("keyguard gone during ${settleMs}ms settle, skip PIN injection")
            return WakeUnlockResult.OK
        }
        // 位数不进任何日志：logcat 随日志导出
        logI("injecting PIN after ${settleMs}ms settle (wake ${wakeCostMs}ms)")

        val injectStartMs = SystemClock.elapsedRealtime()
        if (!withSensitiveInput { injectPin(wm, credential) }) {
            logI("keyguard gone mid-injection, stopped")
            return WakeUnlockResult.OK
        }
        val padMs = PIN_INJECT_BUDGET_MS - (SystemClock.elapsedRealtime() - injectStartMs)
        if (padMs > 0) Thread.sleep(padMs)
        logI("PIN injected")

        val pollStartMs = SystemClock.elapsedRealtime()
        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
            logI("unlocked (PIN accepted) after ${SystemClock.elapsedRealtime() - pollStartMs}ms")
            WakeUnlockResult.OK
        } else {
            // 不重试，避免连续输错触发系统锁定
            logW(
                "still locked ${SystemClock.elapsedRealtime() - pollStartMs}ms after PIN" +
                        " injection — wrong PIN, or bouncer was not ready and swallowed the keys"
            )
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 每个键前都确认 keyguard 还在；@return false 表示锁屏中途消失，余下的没注入 */
    private fun injectPin(wm: WindowManager, credential: String): Boolean {
        for (c in credential) {
            if (keyguardGone(wm)) return false
            injectKey(KeyEvent.KEYCODE_0 + (c - '0'))
            Thread.sleep(DIGIT_GAP_MS)
        }
        // 部分 ROM 会自动提交；补 ENTER 兼容需确认的 PIN，已自动提交的别让 ENTER 落到前台
        if (keyguardGone(wm)) return false
        injectKey(KeyEvent.KEYCODE_ENTER)
        return true
    }

    /** 查不到按还在处理：只有确认消失才放弃注入 */
    private fun keyguardGone(wm: WindowManager): Boolean = wm.isKeyguardLocked == false

    /**
     * 亮屏并回放录制的解锁手势
     * @param gestureJson 空串表示未录制
     */
    fun unlockWithGesture(gestureJson: String): Int = traced("unlock, gesture") { doUnlockWithGesture(gestureJson) }

    private fun doUnlockWithGesture(gestureJson: String): Int {
        val gesture = UnlockGesture.parseOrNull(gestureJson) { logW("$it") }
        if (gesture == null || gesture.steps.isEmpty()) {
            logW("no usable gesture recorded")
            return WakeUnlockResult.GESTURE_EMPTY
        }

        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        wakeAndRequireKeyguard(pm, wm)?.let { return it }

        // 与录制一致，在 keyguard 还在时采样
        val screen = ScreenGeometry.current()
        if (screen == null) {
            logW("display info unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (screen.rotation != gesture.rotation) {
            logW("rotation ${screen.rotation} != recorded ${gesture.rotation}")
            return WakeUnlockResult.GESTURE_SCREEN_MISMATCH
        }
        if (screen.width != gesture.screenWidth || screen.height != gesture.screenHeight) {
            logW(
                "screen ${screen.width}x${screen.height} != recorded" +
                        " ${gesture.screenWidth}x${gesture.screenHeight}, scaling"
            )
        }

        // 录制起点就是亮屏后的锁屏首屏，这里不能再 dismissKeyguard 打乱状态
        Thread.sleep(GESTURE_SETTLE_MS)
        if (keyguardGone(wm)) {
            logI("keyguard gone during settle, skip gesture replay")
            return WakeUnlockResult.OK
        }
        // 槽位与任务注入共用：给在途的一笔留点时间抬起。等不到也照样回放——任务被中途停掉时
        // 残留的槽位不会自己清，在这拒绝就永远解不了锁；回放前后各整体 CANCEL 一次
        if (!pollUntil(CONTACTS_IDLE_TIMEOUT_MS) { !InputControlUtils.hasActiveContacts() }) {
            logW("other contacts still down, replay will cancel them")
        }
        val actions = UnlockGestureReplay.timeline(gesture, screen.width, screen.height)
        // 步骤全是没点的空滑动：回放什么都不按，结局会被说成轨迹失效
        if (actions.none { it is InjectAction.Down }) {
            logW("gesture has no presses to replay")
            return WakeUnlockResult.GESTURE_EMPTY
        }
        // 不报步数：点按录的手势步数就是 PIN 位数
        logI("replaying gesture after ${GESTURE_SETTLE_MS}ms settle")
        val stats = UnlockGestureReplay.execute(actions) { !keyguardGone(wm) }
        if (stats == null) {
            logI("keyguard gone mid-replay, stopped")
            return WakeUnlockResult.OK
        }
        logI("replay done, injected $stats")

        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
            logI("unlocked (gesture accepted)")
            WakeUnlockResult.OK
        } else {
            // 与 PIN 一致：不重试，避免连错触发系统锁定
            val cause = if (stats.allAccepted) {
                "all injected: stale trajectory, or keyguard ignores injected events"
            } else {
                "some injections not delivered: events likely blocked by the system"
            }
            logW("still locked after gesture replay — $cause")
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 只亮屏，不碰 keyguard；录制手势时用，和回放走同一条唤醒路径 */
    fun wakeScreen(): Boolean = ensureScreenOn(ServiceManager.getPowerManager())

    private fun ensureScreenOn(pm: PowerManager): Boolean =
        ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.wakeActions,
            alreadyDone = { pm.isScreenOn(0) },
            perform = { action ->
                when (action) {
                    WakeAction.BINDER -> {
                        if (!pm.wakeUp()) {
                            logW("wakeUp() invoke failed, polling then falling back to keys")
                        }
                    }

                    WakeAction.KEY_WAKEUP -> {
                        logW("injecting KEYCODE_WAKEUP")
                        injectKey(KeyEvent.KEYCODE_WAKEUP)
                    }

                    WakeAction.KEY_POWER -> if (!pm.isScreenOn(0)) {
                        logW("injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == WakeAction.BINDER) {
                    SCREEN_ON_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val on = pollUntil(timeout) { pm.isScreenOn(0) }
                if (!on) logW("screen still off after $action")
                on
            },
        )

    /** 取消后不再发下一步息屏动作，返回值此时无意义，调用方自己查 [isCancelled] */
    private fun ensureScreenOff(pm: PowerManager, isCancelled: () -> Boolean): Boolean {
        return ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.sleepActions,
            alreadyDone = { !pm.isScreenOn(0) },
            perform = { action ->
                if (isCancelled()) return false
                when (action) {
                    SleepAction.BINDER -> {
                        if (!pm.goToSleep()) {
                            logW("goToSleep() invoke failed, polling then falling back to keys")
                        }
                    }

                    SleepAction.KEY_SLEEP -> {
                        logW("injecting KEYCODE_SLEEP")
                        injectKey(KeyEvent.KEYCODE_SLEEP)
                    }

                    SleepAction.KEY_POWER -> if (pm.isScreenOn(0)) {
                        logW("injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == SleepAction.BINDER) {
                    SCREEN_OFF_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val off = pollUntil(timeout) { isCancelled() || !pm.isScreenOn(0) }
                if (!off) logW("screen still on after $action")
                off
            },
        )
    }

    private fun injectKey(keyCode: Int) {
        InputControlUtils.keyDown(keyCode, 0)
        InputControlUtils.keyUp(keyCode, 0)
    }

}

/** PIN 与手势回放期间注入层不记键码/坐标、不回调触控预览 */
internal inline fun <T> withSensitiveInput(block: () -> T): T {
    InputControlUtils.beginSensitiveInput()
    try {
        return block()
    } finally {
        InputControlUtils.endSensitiveInput()
    }
}
