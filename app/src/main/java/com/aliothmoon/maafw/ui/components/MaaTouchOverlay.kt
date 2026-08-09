package com.aliothmoon.maafw.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.runner.PreviewTouchMarker

// 三色对应按下 / 移动 / 抬起，不走主题色：叠在任意画面上都要能看清
private val MarkerDown = Color(0xFF81C784)
private val MarkerMove = Color(0xFFFFD54F)
private val MarkerUp = Color(0xFFE57373)

/**
 * 把注入到虚拟屏的触点画在预览上
 *
 * marker 坐标在虚拟屏坐标系，按 [resolution] 映射到画布；随存活时长淡出
 */
@Composable
fun MaaTouchOverlay(
    markers: List<PreviewTouchMarker>,
    resolution: DisplayResolution,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val now = SystemClock.elapsedRealtime()
        val maxX = (resolution.width - 1).coerceAtLeast(1)
        val maxY = (resolution.height - 1).coerceAtLeast(1)

        markers.forEach { marker ->
            val age = (now - marker.createdAtMs).coerceAtLeast(0L)
            val progress = (age / PreviewTouchMarker.TTL_MS.toFloat()).coerceIn(0f, 1f)
            val alpha = 1f - progress
            if (alpha <= 0f) return@forEach

            val center = Offset(
                x = size.width * marker.x.coerceIn(0, maxX) / maxX.toFloat(),
                y = size.height * marker.y.coerceIn(0, maxY) / maxY.toFloat(),
            )

            when (marker.action) {
                MotionEvent.ACTION_DOWN -> {
                    drawCircle(
                        color = MarkerDown.copy(alpha = alpha * 0.3f),
                        radius = 8.dp.toPx() + 12.dp.toPx() * progress,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx() * alpha),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MarkerDown.copy(alpha = alpha * 0.8f),
                                MarkerDown.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = 12.dp.toPx(),
                        ),
                        radius = 12.dp.toPx(),
                        center = center,
                    )
                    drawCircle(Color.White.copy(alpha = alpha * 0.9f), 2.5.dp.toPx(), center)
                }

                MotionEvent.ACTION_MOVE -> {
                    drawCircle(MarkerMove.copy(alpha = alpha * 0.5f), 5.dp.toPx(), center)
                    drawCircle(Color.White.copy(alpha = alpha * 0.4f), 1.5.dp.toPx(), center)
                }

                MotionEvent.ACTION_UP -> {
                    drawCircle(
                        color = MarkerUp.copy(alpha = alpha * 0.6f),
                        radius = 6.dp.toPx() + 18.dp.toPx() * progress,
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * alpha),
                    )
                    drawCircle(MarkerUp.copy(alpha = alpha * 0.8f), 3.dp.toPx() * (1f - progress), center)
                }
            }
        }
    }
}
