package com.aliothmoon.maafw.runner

import android.os.SystemClock
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.ServiceConnectResult
import com.aliothmoon.maafw.privileged.message
import com.aliothmoon.maafw.privileged.remoteAccessMessage
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
    /** 收尾排在最后：熄屏、通知、会话日志都撤完了才断开特权进程 */
    const val COLLAPSE = -20

    /** 排在所有环境动作之前，收尾时因此最后关：文件开着的窗口覆盖住整轮 */
    const val SESSION_LOG = -10

    /** 只登记本轮计划给遥测，与会话日志同为投递前的观察者 */
    const val TELEMETRY = -8

    /** 紧随会话日志：收尾时排在所有环境动作之后，播报的是环境都撤干净之后的结局 */
    const val NOTIFICATION = -5

    /** 不改环境，排在四个环境挂载物之前：它们都要特权进程 */
    const val PRIVILEGED_CONNECT = -2

    /** 连上之后、唤醒之前：唤醒之后再采，屏幕永远是亮的 */
    const val IDLE_PROBE = -1
    const val AUTO_SLEEP = 0

    /** 要唤醒前的设备状态，又得赶在唤醒、盖屏保之前拦下；自动熄屏只采样，排它前后都行 */
    const val APP_BLACKLIST = 5
    const val WAKE_UNLOCK = 10
    const val SCREEN_SAVER = 20
    const val CLOSE_APP = 30

    /** 收尾排在关闭目标应用之前：结束画面要趁目标应用还在屏上截 */
    const val COMPLETION_SCREENSHOT = 35
    const val COUNTDOWN = 40

    /** 倒计时走完再查一次 */
    const val APP_BLACKLIST_RECHECK = 42

    /** 紧挨投递：倒计时期间用户还可能点开面板 */
    const val OVERLAY_CLEAR = 45

    /** 受理之后最先做：倒计时早已走完，目标应用还没起来 */
    const val SCREEN_POWER_OFF = 48

    /** 只是挂一个监听，排在最后，收尾时最先摘掉 */
    const val WATCHDOG_NOTICE = 50

    /** 同为观察者，排在看门狗之后；收尾时先停 FPS 轮询，再看门狗 */
    const val GAME_FPS = 55

    /** 只写一次日志，没有可撤的 */
    const val DISPLAY_HAZARD_NOTICE = 57

    /** 收尾时最先撤计时器：别的挂载物撤到一半时它不该再到点去 stop */
    const val RUN_DURATION_LIMIT = 60
}

/** 规则开了关屏幕电源、且是后台模式；真关还要开始时设备没在用，见 [screenPowerOffPlanned] */
private val RunContext.wantsScreenPowerOff: Boolean
    get() = (trigger as? RunTrigger.Schedule)?.options?.hardwareScreenOff == true && runMode == RunMode.BACKGROUND

/** 本轮会关屏幕电源：关屏、屏保让位、收起前先上锁都按这一条判 */
internal val RunContext.screenPowerOffPlanned: Boolean
    get() = wantsScreenPowerOff && deviceAtStart.get()?.idle == true

/**
 * 投递前先把特权进程连上
 *
 * 解锁、熄屏采样、投递都要它；连不上时前两步各自回落默认值，拉起失败会被报成「解锁失败」，
 * 真原因就丢了。gating：连不上整轮跑不起来，早拦下早给原因
 *
 * 未授权会在这里当场请求（手动那轮用户在跟前），所以超时要盖住授权弹窗加一次拉起
 */
class PrivilegedConnectHook(private val servicePort: PrivilegedServicePort) : RunEnvHook {

    override val id: String = ID
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.PRIVILEGED_CONNECT
    override val gating: Boolean = true
    override val engageTimeoutMs: Long = ENGAGE_TIMEOUT_MS

    override suspend fun engage(ctx: RunContext): EngageResult =
        when (val result = withContext(MaaDispatchers.IO) { servicePort.connect() }) {
            ServiceConnectResult.Connected -> EngageResult.Skipped()
            is ServiceConnectResult.NotReady ->
                EngageResult.Failed(remoteAccessMessage(result.backend, result.failure))
            is ServiceConnectResult.Failed ->
                EngageResult.Failed(result.message(), error = result.error)
        }

    companion object {
        private const val ENGAGE_TIMEOUT_MS = 60_000L

        /** 进触发日志的 steps，落盘了就别改 */
        const val ID = "privileged-connect"
    }
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
    /** 设了安全锁屏（要密码）；只进失败取证 */
    private val deviceSecure: () -> Boolean,
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
        val startedAt = SystemClock.elapsedRealtime()
        val code = servicePort.callConnecting("unlock", WakeUnlockResult.IPC_FAILED) {
            when (credential) {
                UnlockCredential.Swipe -> it.unlock("")
                is UnlockCredential.Pin -> it.unlock(credential.digits)
                is UnlockCredential.Gesture -> it.unlockWithGesture(credential.json)
            }
        }
        val result = WakeResult.fromCode(code)
        if (result.isUnlocked || !keyguardLocked()) return EngageResult.Skipped()
        recordFailure(ctx, credential, startedAt)
        return EngageResult.Failed(result.message)
    }

    /**
     * 解锁失败多半没人在跟前，只一句原因查不下去：配置摘要与特权侧的步骤耗时写进本轮运行日志
     * （会话日志排在解锁前，没跑起来的一轮也归档）。运行日志可能随推送外发，两段都不带 PIN、位数与轨迹
     */
    private suspend fun recordFailure(ctx: RunContext, credential: UnlockCredential, startedAt: Long) {
        val recordedAt = if (credential is UnlockCredential.Gesture) gestures.recordedAtMs() else null
        ctx.journal.warn(
            ctx.executionId,
            wakeConfigText(
                unlockType = settings.wakeUnlockType.value,
                credential = credential,
                gestureRecordedAtMs = recordedAt,
                passwordRequired = deviceSecure(),
                before = ctx.deviceAtStart.get(),
            ),
        )
        // 旧版特权进程没有这个方法，proxy 读回 null
        val trace = servicePort.callOrDefault("getLastWakeTrace", "") { it.getLastWakeTrace(startedAt).orEmpty() }
        if (trace.isNotBlank()) ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_wake_trace, trace))
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

        // 本轮由 ScreenPowerOffHook 关屏；开始时设备在用的不关屏，屏保照盖
        if (ctx.screenPowerOffPlanned) return EngageResult.Skipped()

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
 * 唤醒之前采「设备在不在用」，写进 [RunContext.deviceAtStart]
 *
 * 只采不改，只采定时触发：手动发起时人就在跟前。熄屏或锁屏都算没在用——没设锁屏的机器熄了屏也不上锁，
 * 只看 keyguard 会把它判成在用
 */
class IdleProbeHook(
    private val servicePort: PrivilegedServicePort,
    private val keyguardLocked: () -> Boolean,
) : RunEnvHook {

    override val id: String = "idle-probe"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.IDLE_PROBE
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.trigger !is RunTrigger.Schedule) return EngageResult.Skipped()
        val screenOn = servicePort.callConnecting("isScreenOn", true) { it.isScreenOn() }
        ctx.deviceAtStart.set(DeviceAtStart(screenOn = screenOn, keyguardLocked = keyguardLocked()))
        return EngageResult.Skipped()
    }
}

/**
 * 定时规则开了「结束后收起」：跑到头（含到时长上限）后断开特权进程、关掉界面
 *
 * 不退进程：开着无障碍时系统会为重绑它立刻重建进程，冷启动时授权观察器拿到首值就去 bind，
 * 特权进程又回来了，等于白退；进程内 unbind 后授权状态不变，不会被拉回。虚拟屏带
 * DESTROY_CONTENT_ON_REMOVAL，特权进程一退目标应用界面随之销毁，不会跳到主屏
 *
 * 手动停止不收：多半是用户要接手现场。有定时在排队也不收：它要接着用这条连接
 */
class CollapseAfterRunHook(
    private val servicePort: PrivilegedServicePort,
    private val runQueued: () -> Boolean,
    private val closeUi: suspend () -> Unit,
) : RunEnvHook {

    override val id: String = "collapse-after-run"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.COLLAPSE
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val options = (ctx.trigger as? RunTrigger.Schedule)?.options ?: return EngageResult.Skipped()
        if (!options.collapseAfterTask) return EngageResult.Skipped()
        return EngageResult.Engaged(Release { reason ->
            val ranToEnd = reason is RunEndReason.Ran &&
                (reason.result !is ExecutionResult.Cancelled || ctx.stoppedAtLimit.get())
            if (!ranToEnd) return@Release
            if (runQueued()) {
                Timber.i("collapse skipped: another run is waiting")
                return@Release
            }
            // 关过屏幕电源而系统还醒着：特权进程一退面板就被点亮，先上锁休眠
            if (ctx.screenPowerOffPlanned) {
                servicePort.callOrDefault("lockAndSleep", WakeUnlockResult.IPC_FAILED) { it.lockAndSleep() }
            }
            Timber.i("collapsing after run: unbinding the privileged process")
            servicePort.unbind()
            closeUi()
        })
    }
}

/** 跑完自动上锁息屏；「用户本来就在用手机」读 [RunContext.deviceAtStart]，engage 时就捕进闭包 */
class AutoSleepHook(private val servicePort: PrivilegedServicePort) : RunEnvHook {

    override val id: String = "auto-sleep"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.AUTO_SLEEP
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val options = (ctx.trigger as? RunTrigger.Schedule)?.options ?: return EngageResult.Skipped()
        if (!options.autoSleepAfterTask) return EngageResult.Skipped()

        val tookOverIdleDevice = ctx.deviceAtStart.get()?.idle == true
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
 * 定时规则开了「关闭屏幕电源」：受理之后（倒计时早已走完）关掉物理屏，只关不恢复，代替屏保
 *
 * 只在后台模式、且本轮开始时设备没在用才关：人正拿着手机就把屏关了说不过去。面板关着时系统仍算亮屏，
 * 按电源键由系统点亮；特权进程退出时系统还醒着才兜底点亮。结果如实落进触发日志的步骤
 */
class ScreenPowerOffHook(private val servicePort: PrivilegedServicePort) : RunEnvHook {

    override val id: String = "screen-power-off"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.SCREEN_POWER_OFF
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (!ctx.wantsScreenPowerOff) return EngageResult.Skipped()
        if (!ctx.screenPowerOffPlanned) {
            ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_screen_power_off_skipped_awake))
            return EngageResult.Skipped()
        }
        if (!servicePort.callOrDefault("setDisplayPowerSync", false) { it.setDisplayPowerSync(false) }) {
            val reason = uiTextOf(R.string.run_log_screen_power_off_failed)
            ctx.journal.warn(ctx.executionId, reason)
            return EngageResult.Failed(reason)
        }
        ctx.journal.info(ctx.executionId, uiTextOf(R.string.run_log_screen_power_off))
        // 收尾什么都不做：同手动熄屏挂机，一轮跑完屏幕仍关着，由用户按电源键点亮。
        // 仍报 Engaged 而不是 Skipped，触发日志的步骤才记得下「关过屏」
        return EngageResult.Engaged(Release { })
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
