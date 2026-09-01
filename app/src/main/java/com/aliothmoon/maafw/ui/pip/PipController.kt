package com.aliothmoon.maafw.ui.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.compositionLocalOf
import com.aliothmoon.maafw.runner.DisplayResolution
import timber.log.Timber

/** [sourceRect] 是预览区在 window 中的位置，给进入小窗的过渡动画用，拿不到传 null */
data class PipRequest(
    val resolution: DisplayResolution,
    val sourceRect: Rect? = null,
)

/** 任务页写 [pipRequest]，API 28~30 的 onUserLeaveHint 兜底读它；只存参数不存 Activity 引用 */
interface PipHost {
    var pipRequest: PipRequest?
}

/** 不直接用 [Rational]：它在纯 JVM 单测里是抛异常的 stub，clamp 逻辑就没法测了 */
data class PipAspectRatio(val numerator: Int, val denominator: Int)

/**
 * 判断 PIP 的唯一来源，由 MainActivity 在最外层提供
 *
 * **不能用 `staticCompositionLocalOf`**：它一改值就把整棵子树无条件重组、跳过优化直接失效，
 * 而提供点在 Activity 最外层——实测进出小窗各触发一次全树重组，展开那一帧 404ms、掉 51 帧
 *
 * 背后也必须是 snapshot state 而不是 StateFlow：`collectAsState` 要等 `AndroidUiDispatcher`
 * 排到下一帧，窗口尺寸变化会抢在它前面落到测量里，AppRoot 那个钉尺寸的 layout 就记错了
 */
val LocalIsInPip = compositionLocalOf { false }

/**
 * 系统画中画：后台模式任务运行中，回桌面时自动缩为小窗继续显示虚拟屏画面
 *
 * 纯画面，不带 RemoteAction——PIP 窗口的点击被系统消费为「展开控制条」，无法转发触摸到虚拟屏
 */
object PipController {

    /** 系统对画中画宽高比的硬性区间 2.39:1，越界 setAspectRatio 会抛 IllegalArgumentException */
    private const val MAX_RATIO_NUM = 239
    private const val MAX_RATIO_DEN = 100

    internal const val MAX_RATIO = MAX_RATIO_NUM.toFloat() / MAX_RATIO_DEN
    internal const val MIN_RATIO = 1f / MAX_RATIO

    /** 非法尺寸的回落档；虚拟屏两档预览都是 16:9 */
    internal val FALLBACK_RATIO = PipAspectRatio(16, 9)

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * 预览分辨率当前只有 16:9 两档，走不到边界；
     * 但 DisplayResolution 收任意宽高，越界会在 setAspectRatio 直接崩，故留此防线
     */
    fun clampAspectRatio(width: Int, height: Int): PipAspectRatio {
        if (width <= 0 || height <= 0) return FALLBACK_RATIO
        val ratio = width.toFloat() / height
        return when {
            ratio > MAX_RATIO -> PipAspectRatio(MAX_RATIO_NUM, MAX_RATIO_DEN)
            ratio < MIN_RATIO -> PipAspectRatio(MAX_RATIO_DEN, MAX_RATIO_NUM)
            else -> PipAspectRatio(width, height)
        }
    }

    /** API 31+ 顺带开合 auto-enter；低版本只更新参数，进入靠 [enterNow] 兜底 */
    fun updateParams(activity: Activity, autoEnter: Boolean, request: PipRequest) {
        if (!isSupported(activity)) return
        runCatching {
            activity.setPictureInPictureParams(buildParams(request, autoEnter))
        }.onFailure {
            Timber.w(it, "setPictureInPictureParams failed")
        }
    }

    /**
     * API 28~30 没有 auto-enter，只能在 onUserLeaveHint 里手动进入
     *
     * 手势导航下 onUserLeaveHint 不保证触发，进不去就算了，不影响回桌面
     */
    fun enterNow(activity: Activity, request: PipRequest): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        if (!isSupported(activity)) return false
        if (activity.isInPictureInPictureMode) return false
        return runCatching {
            activity.enterPictureInPictureMode(buildParams(request, autoEnter = false))
        }.onFailure {
            Timber.w(it, "enterPictureInPictureMode failed")
        }.getOrDefault(false)
    }

    private fun buildParams(request: PipRequest, autoEnter: Boolean): PictureInPictureParams {
        val ratio = clampAspectRatio(request.resolution.width, request.resolution.height)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(ratio.numerator, ratio.denominator))
        request.sourceRect?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }
}
