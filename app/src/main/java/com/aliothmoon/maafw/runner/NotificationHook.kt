package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.notification.NotificationImage
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.callWithTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * 整轮结束时播报结局
 *
 * 借 [RunEnvHook] 的形状而不是让 UI 观察终态：定时触发时 Activity 可能压根不在，
 * 挂在 ViewModel 上的观察者会整轮漏掉。与 [SessionLogHook] 同一路数——
 * `Release(reason)` 正好是「结局已定」那一刻
 *
 * engage 什么都不做，只为占一个收尾位。不 gating：推送发不出去不该反过来拦住任务
 */
class NotificationHook(private val center: NotificationCenter) : RunEnvHook {

    override val id: String = "notification"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.NOTIFICATION
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult =
        EngageResult.Engaged { reason ->
            center.onRunFinished(
                reason,
                stoppedAtLimit = ctx.stoppedAtLimit.get(),
                screenshot = ctx.endScreenshot.get()?.let(::NotificationImage),
            )
        }
}

/**
 * 完成推送附带的结束画面
 *
 * 自己占一个收尾位而不是让 [NotificationHook] 截：那个排在最后，收尾到它时目标应用已被
 * `close-target-app` 关掉、屏保撤了、屏幕也熄了，截到的是空屏
 */
class CompletionScreenshotHook(
    private val center: NotificationCenter,
    private val servicePort: PrivilegedServicePort,
) : RunEnvHook {

    override val id: String = "completion-screenshot"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.COMPLETION_SCREENSHOT
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (!center.wantsCompletionScreenshot()) return EngageResult.Skipped()
        return EngageResult.Engaged(Release { reason ->
            if (reason !is RunEndReason.Ran || reason.result !is ExecutionResult.Completed) return@Release
            val jpeg = servicePort.callWithTimeout(CAPTURE_TIMEOUT) { it.captureFrameJpeg(JPEG_QUALITY) }
            ctx.endScreenshot.set(jpeg?.takeIf { it.isNotEmpty() })
        })
    }

    private companion object {
        val CAPTURE_TIMEOUT = 5.seconds

        /** 特权侧先缩到长边 1280，编码后约 150–250KB */
        const val JPEG_QUALITY = 85
    }
}
