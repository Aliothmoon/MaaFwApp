package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.delay
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

    /** 紧随会话日志：收尾时排在所有环境动作之后，播报的是环境都撤干净之后的结局 */
    const val NOTIFICATION = -5
    const val AUTO_SLEEP = 0
    const val WAKE_UNLOCK = 10
    const val SCREEN_SAVER = 20
    const val CLOSE_APP = 30
    const val COUNTDOWN = 40

    /** 只是挂一个监听，排在最后，收尾时最先摘掉 */
    const val WATCHDOG_NOTICE = 50
}

/**
 * 亮屏解锁
 *
 * gating：解锁不成还往下跑，就是对着锁屏识别到超时，几十分钟白烧
 */
class WakeUnlockHook(
    private val servicePort: PrivilegedServicePort,
    private val settings: AppSettingsGateway,
) : RunEnvHook {

    override val id: String = "wake-unlock"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.WAKE_UNLOCK
    override val gating: Boolean = true

    override suspend fun engage(ctx: RunContext): EngageResult {
        // 只对定时触发生效（对齐 MaaMeow 的「定时任务解锁方式」）：手动 Start 时
        // 用户正对着亮屏解锁的手机按按钮，解一次是空操作
        if (ctx.trigger !is RunTrigger.Schedule) return EngageResult.Skipped()
        if (!settings.wakeUnlockEnabled.value) return EngageResult.Skipped()

        val credential = settings.wakeCredential.value
        val code = servicePort.callOrDefault("unlock", WakeUnlockResult.IPC_FAILED) {
            it.unlock(credential)
        }
        return when (code) {
            WakeUnlockResult.OK, WakeUnlockResult.NO_KEYGUARD -> EngageResult.Skipped()
            else -> EngageResult.Failed(wakeFailureText(code))
        }
    }

    private fun wakeFailureText(code: Int): UiText = when (code) {
        WakeUnlockResult.CREDENTIAL_REQUIRED -> uiTextOf(R.string.wake_unlock_need_pin)
        WakeUnlockResult.CREDENTIAL_REJECTED -> uiTextOf(R.string.wake_unlock_pin_rejected)
        WakeUnlockResult.WAKE_FAILED -> uiTextOf(R.string.wake_unlock_screen_off)
        WakeUnlockResult.UNSUPPORTED -> uiTextOf(R.string.wake_unlock_unsupported)
        else -> uiTextOf(R.string.wake_unlock_failed, code)
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
            ctx.journal.warn(uiTextOf(R.string.run_log_screen_saver_skipped))
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
            // 只认自然跑完：投递被拒或用户手动停时把人家的应用关掉，太粗暴
            if (reason is RunEndReason.Ran && reason.result !is ExecutionResult.Cancelled) {
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

        val tookOverIdleDevice = !servicePort.callOrDefault("isScreenOn", true) { it.isScreenOn() }
        val skipIfAwake = options.skipAutoSleepIfAwake
        // 两个采样值都在这里捕进闭包：收尾时再去读，读到的是那时的屏幕状态与开关，不是本轮开始时的
        return EngageResult.Engaged(Release { reason ->
            when {
                reason !is RunEndReason.Ran ->
                    ctx.journal.info(uiTextOf(R.string.run_log_auto_sleep_skipped_not_run))

                skipIfAwake && !tookOverIdleDevice ->
                    ctx.journal.info(uiTextOf(R.string.run_log_auto_sleep_skipped_awake))

                else -> servicePort.callOrDefault("lockAndSleep", WakeUnlockResult.IPC_FAILED) {
                    it.lockAndSleep()
                }
            }
        })
    }
}

/**
 * 投递前倒计时：给用户一个「提前开始 / 取消本次」的窗口
 *
 * 只在**前台模式**的定时触发上挂：后台模式没人看着，到点直接投。
 * 闹钟提前 30s 响起，这里等到的是计约定时刻；窗口内点了「立即开始」就提前投，
 * 点了「取消」就中止整轮，什么都没点就在到点那一刻准时投。
 *
 * gating：用户点了取消就该中止整轮，而不是「等完了照跑」
 */
class CountdownHook(
    /** 时钟抽象，单测喂虚拟时间 */
    private val now: () -> Long = { System.currentTimeMillis() },
) : RunEnvHook {

    /** 倒计时要等的是「计约定时刻」而非固定 30s，外层兜底超时给宽一点 */
    override val engageTimeoutMillis: Long = 60_000L

    override val id: String = "countdown"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.COUNTDOWN
    override val gating: Boolean = true

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.runMode != RunMode.FOREGROUND) return EngageResult.Skipped()
        val trigger = ctx.trigger as? RunTrigger.Schedule ?: return EngageResult.Skipped()
        val deadline = trigger.scheduledAtEpochMs ?: return EngageResult.Skipped()
        if (deadline <= now()) return EngageResult.Skipped()

        while (true) {
            // 先看「立即开始」：两个都置位时以它为准（同 MaaMeow），
            // 两个布尔分不出先后，而「点了取消又点开始」比反过来常见得多
            if (ctx.signals.startNowRequested) return EngageResult.Skipped()
            if (ctx.signals.cancelRequested) {
                return EngageResult.Failed(
                    uiTextOf(R.string.run_countdown_cancelled),
                    NotRunCause.Cancelled,
                )
            }
            val remainingMs = deadline - now()
            if (remainingMs <= 0) break
            val remainingSeconds = ((remainingMs + 999) / 1000).toInt()
            ctx.progress.report(id, uiTextOf(R.string.run_countdown_remaining, remainingSeconds))
            delay(minOf(1_000L, remainingMs))
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
