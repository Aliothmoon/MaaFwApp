package com.aliothmoon.maafw.overlay.border

import androidx.core.graphics.toColorInt

/** 运行边框的样式；默认值即当前唯一用法，留参数只为将来按状态换色 */
data class BorderStyle(
    val widthDp: Float = 2f,
    /** 扫描渐变的色环，首尾同色才闭合 */
    val colors: IntArray = DEFAULT_RAINBOW_COLORS,
    /** 转一圈的时长 */
    val animationDurationMs: Long = 3000L,
) {
    // IntArray 的 equals 是引用比较，data class 自动生成的判等会把两个内容相同的样式判成不等，
    // 于是 BorderOverlayManager 每次 show 都以为样式变了而重建视图
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BorderStyle
        return widthDp == other.widthDp &&
            colors.contentEquals(other.colors) &&
            animationDurationMs == other.animationDurationMs
    }

    override fun hashCode(): Int {
        var result = widthDp.hashCode()
        result = 31 * result + colors.contentHashCode()
        result = 31 * result + animationDurationMs.hashCode()
        return result
    }

    companion object {
        val DEFAULT_RAINBOW_COLORS = intArrayOf(
            "#FF0000".toColorInt(),
            "#FF7F00".toColorInt(),
            "#FFFF00".toColorInt(),
            "#00FF00".toColorInt(),
            "#00FFFF".toColorInt(),
            "#0000FF".toColorInt(),
            "#8B00FF".toColorInt(),
            "#FF0000".toColorInt(),
        )
    }
}
