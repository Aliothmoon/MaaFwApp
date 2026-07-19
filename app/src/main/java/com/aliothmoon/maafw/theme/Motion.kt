package com.aliothmoon.maafw.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * 统一动效规范（M3 Expressive emphasized 缓动）。
 * 进入用减速曲线（先快后慢），退出用加速曲线（先慢后快）。
 */
object MaaMotion {
    const val DURATION_SHORT = 150
    const val DURATION_MEDIUM = 300
    const val DURATION_LONG = 450

    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> enter(duration: Int = DURATION_MEDIUM): FiniteAnimationSpec<T> =
        tween(duration, easing = EmphasizedDecelerate)

    fun <T> exit(duration: Int = DURATION_SHORT): FiniteAnimationSpec<T> =
        tween(duration, easing = EmphasizedAccelerate)
}
