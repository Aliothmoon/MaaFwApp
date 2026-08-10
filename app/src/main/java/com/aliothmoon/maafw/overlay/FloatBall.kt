package com.aliothmoon.maafw.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme

/**
 * 前台模式的常驻悬浮球
 *
 * 尺寸压到 32dp：它盖在目标应用画面上，再大就开始妨碍识别区域了
 * 运行中做呼吸动画——静止的小圆点在满屏画面里根本注意不到
 */
@Composable
fun FloatBall(
    phase: RunnerPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = MaaTheme.palette
    val target = when (phase) {
        RunnerPhase.Running -> palette.success.content
        RunnerPhase.Preparing, RunnerPhase.Stopping -> palette.warning.content
        is RunnerPhase.Unavailable -> MaterialTheme.colorScheme.error
        RunnerPhase.Idle -> MaterialTheme.colorScheme.primary
    }
    val color by animateColorAsState(target.copy(alpha = 0.85f), tween(300))

    val breathing by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
    )

    val description = stringResource(
        when (phase) {
            RunnerPhase.Idle -> R.string.overlay_ball_idle
            RunnerPhase.Preparing -> R.string.overlay_ball_preparing
            RunnerPhase.Running -> R.string.overlay_ball_running
            RunnerPhase.Stopping -> R.string.overlay_ball_stopping
            is RunnerPhase.Unavailable -> R.string.overlay_ball_unavailable
        },
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(BALL_SIZE)
            .clip(CircleShape)
            .border(MaaDesignTokens.Separator.thickness, Color.White.copy(alpha = 0.15f), CircleShape)
            .then(if (phase == RunnerPhase.Running) Modifier.alpha(breathing) else Modifier)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = color,
        // 球盖在目标应用上需要体量感；不跟 Semi 卡片的 0 elevation
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (phase) {
                    RunnerPhase.Running -> Icons.Outlined.PlayArrow
                    is RunnerPhase.Unavailable -> Icons.Outlined.Warning
                    else -> Icons.Outlined.Check
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
        }
    }
}

/** 悬浮球的直径；它盖在别人画面上，属一次性视觉尺寸，不进 Spacing */
private val BALL_SIZE = 32.dp
