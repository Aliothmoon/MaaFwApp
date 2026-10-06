package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.schedule.UnlockGestureReader
import com.aliothmoon.maafw.schedule.WakeResult
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 取当前服务面做一次调用，拿不到或抛了就用 [default]
 *
 * 用 `serviceOrNull` 而不是 `useService`：收尾时特权进程可能已经断了，
 * 那会儿不该反过来触发重连与授权请求——撤个屏保而已
 */
private inline fun <R> PrivilegedServicePort.callOrDefault(
    name: String,
    default: R,
    action: (RemoteService) -> R,
): R {
    val service = serviceOrNull() ?: return default
    return runCatching { action(service) }
        .onFailure { Timber.w(it, "%s failed", name) }
        .getOrDefault(default)
}

/**
 * 投递前的环境动作：先把服务连上再调，连不上或抛了才用 [default]
 *
 * 定时冷启动时特权进程多半还在连，只看现成连接会拿到默认值：解锁因此把整轮拦掉，
 * 自动熄屏也采不到真实的亮屏状态。runner 投递时本来就要 `useService`，这里只是早一步
 */
private suspend fun <R> PrivilegedServicePort.callConnecting(
    name: String,
    default: R,
    action: (RemoteService) -> R,
): R = withContext(MaaDispatchers.IO) {
    try {
        useService { action(it) }
    } catch (e: TimeoutCancellationException) {
        // 等连接超时与外层 hook 超时是同一个异常类型，外层取消了就别吞
        ensureActive()
        Timber.w(e, "%s: service not connected in time", name)
        default
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "%s failed", name)
        default
    }
}

/**
 * 四个环境挂载物的 order 是一组，改一个要看另外三个
 *
 * ```
 * engage   AUTO_SLEEP(0) -> WAKE_UNLOCK(10) -> SCREEN_SAVER(20) -> CLOSE_APP(30)
 * release  CLOSE_APP     -> SCREEN_SAVER    -> WAKE_UNLOCK      -> AUTO_SLEEP
 * ```
 *
 * 会话日志与通知播报不在这一组（[HookOrder.SESSION_LOG] / [HookOrder.NOTIFICATION]）：
 * 它们不改设备环境，排在两端只为把整轮夹在中间
 *
 * 收尾逆序正好是「关应用 → 掀屏保 → 熄屏」这个物理顺序。
 * 自动熄屏排在最前只为**采样**：它要知道本轮开始时手机是不是本来就醒着，
 * 那个值一旦唤醒过就再也采不到了
 *
 * 倒计时排最后：等待期间屏幕该已经亮着、屏保该已经盖好，用户看到的才是最终态
 */
internal object HookOrder {
    /** 排在所有环境动作之前，收尾时因此最后关：文件开着的窗口覆盖住整轮 */
    const val SESSION_LOG = -10

    /** 只登记本轮计划给遥测，与会话日志同为投递前的观察者 */
    const val TELEMETRY = -8

    /** 紧随会话日志：收尾时排在所有环境动作之后，播报的是环境都撤干净之后的结局 */
    const val NOTIFICATION = -5
    const val AUTO_SLEEP = 0
    const val WAKE_UNLOCK = 10
    const val SCREEN_SAVER = 20
    const val CLOSE_APP = 30
    const val COUNTDOWN = 40

    /** 只是挂一个监听，排在最后，收尾时最先摘掉 */
    const val WATCHDOG_NOTICE = 50

    /** 同为观察者，排在看门狗之后；收尾时先停 FPS 轮询，再看门狗 */
    const val GAME_FPS = 55

    /** 只写一次日志，没有可撤的 */
    const val DISPLAY_HAZARD_NOTICE = 57

    /** 收尾时最先撤计时器：别的挂载物撤到一半时它不该再到点去 stop */
    const val RUN_DURATION_LIMIT = 60
}

/**
 * 亮屏解锁
 *
 * gating：解锁不成还往下跑，就是对着锁屏识别到超时，几十分钟白烧。
 * 但只在锁屏还在时才拦（对齐 MaaMeow）：解锁报失败而 [keyguardLocked] 已是 false——
 * 手机本就开着、或等待期间人脸先解开了——照常往下跑
 */
class WakeUnlockHook(
    private val servicePort: PrivilegedServicePort,
    private val settings: AppSettingsGateway,
    private val gestures: UnlockGestureReader,
    private val keyguardLocked: () -> Boolean,
) : RunEnvHook {

    override val id: String = ID
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.WAKE_UNLOCK
    override val gating: Boolean = true

    /** 亮屏三级回退 + bouncer 等待 + 手势回放 + 5 秒确认，默认 30 秒会卡在边上 */
    override val engageTimeoutMs: Long = ENGAGE_TIMEOUT_MS

    override suspend fun engage(ctx: RunContext): EngageResult {
        // 只对定时触发生效（对齐 MaaMeow 的「定时任务解锁方式」）：手动 Start 时
        // 用户正对着亮屏解锁的手机按按钮，解一次是空操作。没有总开关：到点总要亮屏
        if (ctx.trigger !is RunTrigger.Schedule) return EngageResult.Skipped()

        val credential = UnlockCredential.of(
            type = settings.wakeUnlockType.value,
            pin = settings.wakeCredential.value,
            gestureJson = if (settings.wakeUnlockType.value == UnlockCredential.TYPE_GESTURE) {
                gestures.readJson()
            } else {
                ""
            },
        )
        val code = servicePort.callConnecting("unlock", WakeUnlockResult.IPC_FAILED) {
            when (credential) {
                UnlockCredential.Swipe -> it.unlock("")
                is UnlockCredential.Pin -> it.unlock(credential.digits)
                is UnlockCredential.Gesture -> it.unlockWithGesture(credential.json)
            }
        }
        val result = WakeResult.fromCodeOrNull(code)
        if (result?.isUnlocked == true || !keyguardLocked()) return EngageResult.Skipped()
        return EngageResult.Failed(result?.message ?: uiTextOf(R.string.wake_result_unknown, code))
    }

    companion object {
        /** 亮屏、bouncer 与确认留 60 秒，再加手势回放的上限：录制最长 90 秒，回放不会比它长 */
        private const val ENGAGE_TIMEOUT_MS = 60_000L + 90_000L

        /** 触发日志按它认出「卡在解锁这一步」，落盘了就别改 */
        const val ID = "wake-unlock"
    }
}

/**
 * 后台模式运行期盖屏保
 *
 * 只在后台模式挂：前台模式采的是主屏，盖上去会被一起截进识别
 */
class ScreenSaverHook(
    private val settings: AppSettingsGateway,
    private val screenSaver: RunScreenSaver,
) : RunEnvHook {

    override val id: String = "screen-saver"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.SCREEN_SAVER
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.runMode != RunMode.BACKGROUND) return EngageResult.Skipped()
        if (!settings.screenSaverEnabled.value) return EngageResult.Skipped()

        // 只有确实是本轮盖上的才登记撤销：用户自己手动盖的那份不归这一轮管
        if (!screenSaver.show()) {
            ctx.journal.warn(ctx.executionId, uiTextOf(R.string.run_log_screen_saver_skipped))
            return EngageResult.Skipped()
        }
        return EngageResult.Engaged(Release { screenSaver.hide() })
    }
}

/**
 * 跑完强停目标应用；engage 什么都不做，只为占一个收尾位
 *
 * 两层开关并存，全局优先：全局开了就每一轮都关（含手动 Start），关着才回落到
 * 这条定时规则自己的选项——规则级那条只能管定时触发，手动那轮压根没有 options
 */
class CloseTargetAppHook(
    private val servicePort: PrivilegedServicePort,
    private val settings: AppSettingsGateway,
) : RunEnvHook {

    override val id: String = "close-target-app"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.CLOSE_APP
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        // 前台模式没有虚拟屏，看门狗从不起来，特权侧也就没有目标包名可关
        if (ctx.runMode != RunMode.BACKGROUND) return EngageResult.Skipped()
        val perRule = (ctx.trigger as? RunTrigger.Schedule)?.options?.closeAppAfterTask == true
        if (!settings.closeAppAfterTask.value && !perRule) return EngageResult.Skipped()

        return EngageResult.Engaged(Release { reason ->
            // 投递被拒或用户手动停时把人家的应用关掉，太粗暴；时长上限到点停的不算用户接手，照关
            val ranToEnd = reason is RunEndReason.Ran &&
                (reason.result !is ExecutionResult.Cancelled || ctx.stoppedAtLimit.get())
            if (ranToEnd) {
                servicePort.callOrDefault("stopTargetApp", false) { it.stopTargetApp() }
            }
        })
    }
}

/**
 * 跑完自动上锁息屏
 *
 * order 最小不是因为它先做事——engage 只采样。它要在**唤醒之前**读到屏幕状态，
 * 唤醒之后 isScreenOn 永远是 true，「用户本来就在用手机」就判不出来了
 */
class AutoSleepHook(private val servicePort: PrivilegedServicePort) : RunEnvHook {

    override val id: String = "auto-sleep"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.AUTO_SLEEP
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val options = (ctx.trigger as? RunTrigger.Schedule)?.options ?: return EngageResult.Skipped()
        if (!options.autoSleepAfterTask) return EngageResult.Skipped()

        val tookOverIdleDevice = !servicePort.callConnecting("isScreenOn", true) { it.isScreenOn() }
        val skipIfAwake = options.skipAutoSleepIfAwake
        // 两个采样值都在这里捕进闭包：收尾时再去读，读到的是那时的屏幕状态与开关，不是本轮开始时的
        return EngageResult.Engaged(Release { reason ->
            when {
                reason !is RunEndReason.Ran ->
                    ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_auto_sleep_skipped_not_run))

                skipIfAwake && !tookOverIdleDevice ->
                    ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_auto_sleep_skipped_awake))

                else -> servicePort.callOrDefault("lockAndSleep", WakeUnlockResult.IPC_FAILED) {
                    it.lockAndSleep()
                }
            }
        })
    }
}

/**
 * 投递前倒计时，给用户一个反悔的窗口
 *
 * gating：用户点了取消就该中止整轮，而不是「等完了照跑」
 *
 * 只在有打断面的调用方那里才有意义——[RunContext.signals] 默认实现永不置位，
 * 那样它就退化成一段纯粹的延迟。首页手动 Start 不挂它：用户刚按下按钮，不需要再问一遍
 */
object CountdownHook : RunEnvHook {

    /** 与 MaaMeow 的 `LaunchRequest.DEFAULT_COUNTDOWN_SECONDS` 一致，同样不开放配置 */
    private const val COUNTDOWN_SECONDS = 30
    private const val TIMEOUT_MARGIN_MS = 10_000L

    override val id: String = "countdown"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.COUNTDOWN
    override val gating: Boolean = true

    // 倒计时满打满算就是 30 秒，外加每秒一次的通知刷新，套默认 30 秒超时必然先被判超时
    override val engageTimeoutMs: Long = COUNTDOWN_SECONDS * 1_000L + TIMEOUT_MARGIN_MS

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.trigger !is RunTrigger.Schedule) return EngageResult.Skipped()

        for (remaining in COUNTDOWN_SECONDS downTo 1) {
            // 先看「立即开始」：两个都置位时以它为准（同 MaaMeow），
            // 两个布尔分不出先后，而「点了取消又点开始」比反过来常见得多
            if (ctx.signals.startNowRequested) break
            if (ctx.signals.cancelRequested) {
                return EngageResult.Failed(
                    uiTextOf(R.string.run_countdown_cancelled),
                    NotRunCause.Cancelled,
                )
            }
            ctx.progress.report(id, uiTextOf(R.string.run_countdown_remaining, remaining))
            delay(1_000)
        }
        // 最后再看一眼：整个等待期间用户都可能点取消，包括最后一秒
        if (ctx.signals.cancelRequested && !ctx.signals.startNowRequested) {
            return EngageResult.Failed(
                uiTextOf(R.string.run_countdown_cancelled),
                NotRunCause.Cancelled,
            )
        }
        return EngageResult.Skipped()
    }
}

/**
 * 屏保的开关面
 *
 * 挂载物不直接拿 `ScreenSaverOverlayManager`：那东西要 Application 上下文与窗口，
 * 隔一层这几个 hook 才进得了 JVM 单测
 */
interface RunScreenSaver {
    /** 返回是否确实由本次调用盖上 */
    suspend fun show(): Boolean
    suspend fun hide()
}
