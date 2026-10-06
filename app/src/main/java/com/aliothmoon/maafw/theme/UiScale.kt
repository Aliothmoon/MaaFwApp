package com.aliothmoon.maafw.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 页面缩放（移植自 MaaMeow 的 UiScale）
 *
 * 改写的是 [Density.density]：控件、间距、文字一起缩放；系统字号仍由 fontScale 单独生效，
 * 两者叠乘。存储值 [AUTO] 表示按屏幕推荐，手动档为 [MIN]–[MAX]
 */
object UiScale {

    const val AUTO = 0
    const val MIN = 80
    const val MAX = 110

    /** 悬浮窗窗口尺寸是固定的，系统大字体照单全收会把面板挤爆，夹在这个区间里 */
    const val OVERLAY_FONT_SCALE_MIN = 0.85f
    const val OVERLAY_FONT_SCALE_MAX = 1.3f

    private const val REF_WIDTH_DP = 360
    private const val NARROW_WIDTH_DP = 320
    private const val REF_SCALE = 95
    private const val NARROW_SCALE = 90

    /** `"auto"` / `"0"` 为自动；[MIN]–[MAX] 以外的值一律回落自动 */
    fun parse(raw: String): Int {
        val n = raw.toIntOrNull() ?: return AUTO
        return if (n in MIN..MAX) n else AUTO
    }

    fun format(scale: Int): String = if (scale == AUTO) "auto" else scale.coerceIn(MIN, MAX).toString()

    /**
     * 按最小宽度推荐：360dp 及以上 95%，320dp 及以下 90%，中间线性过渡；
     * 窄屏又开了系统大字时再让 5%，只让出给变大的字，控件不跟着重缩
     */
    fun recommended(smallestWidthDp: Int, fontScale: Float): Int {
        val sw = if (smallestWidthDp <= 0) REF_WIDTH_DP else smallestWidthDp
        var scale = when {
            sw >= REF_WIDTH_DP -> REF_SCALE
            sw <= NARROW_WIDTH_DP -> NARROW_SCALE
            else -> NARROW_SCALE +
                (sw - NARROW_WIDTH_DP) * (REF_SCALE - NARROW_SCALE) / (REF_WIDTH_DP - NARROW_WIDTH_DP)
        }
        if (sw < REF_WIDTH_DP && fontScale > 1.3f) scale -= 5
        return scale.coerceIn(MIN, MAX)
    }

    /** 实际生效的百分比 */
    fun resolve(stored: Int, smallestWidthDp: Int, fontScale: Float): Int =
        if (stored == AUTO) recommended(smallestWidthDp, fontScale) else stored.coerceIn(MIN, MAX)

    fun clampOverlayFontScale(fontScale: Float): Float =
        fontScale.coerceIn(OVERLAY_FONT_SCALE_MIN, OVERLAY_FONT_SCALE_MAX)
}

/**
 * 按 [scale]（存储值，含 [UiScale.AUTO]）改写子树的 density
 *
 * @param overlay 悬浮窗：额外夹住系统 fontScale，见 [UiScale.OVERLAY_FONT_SCALE_MIN]
 */
@Composable
fun ProvideUiScale(scale: Int, overlay: Boolean = false, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val effective = UiScale.resolve(scale, LocalConfiguration.current.smallestScreenWidthDp, base.fontScale)
    val fontScale = if (overlay) UiScale.clampOverlayFontScale(base.fontScale) else base.fontScale
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density * effective / 100f, fontScale = fontScale),
        content = content,
    )
}
