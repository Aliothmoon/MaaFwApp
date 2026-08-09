package com.aliothmoon.maafw.ui.components

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.aliothmoon.maafw.runner.DisplayResolution

/**
 * 虚拟屏预览面
 *
 * 只在 Surface 尺寸等于虚拟屏尺寸时才上报：`setFixedSize` 是异步的，
 * 提前把还是布局尺寸的 Surface 交给特权进程，画面会按错误比例贴上去
 * 同一个 Surface 不重复上报，避免 surfaceChanged 抖动时反复跨进程调用
 */
@Composable
fun MaaPreviewSurface(
    resolution: DisplayResolution,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    val currentResolution by rememberUpdatedState(resolution)
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    var reportedSurface by remember { mutableStateOf<Surface?>(null) }

    // 离开组合（切 tab / 退出）也要解绑，否则特权进程会往已销毁的 Surface 贴图
    DisposableEffect(Unit) {
        onDispose {
            if (reportedSurface != null) {
                reportedSurface = null
                currentDestroyed()
            }
        }
    }

    Box(
        modifier = modifier.aspectRatio(resolution.aspectRatio),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setFormat(PixelFormat.RGBA_8888)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val res = currentResolution
                            holder.setFixedSize(res.width, res.height)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            val res = currentResolution
                            if (width != res.width || height != res.height) return
                            if (reportedSurface == holder.surface) return
                            reportedSurface = holder.surface
                            currentAvailable(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            reportedSurface = null
                            currentDestroyed()
                        }
                    })
                }
            },
            update = { view ->
                val res = currentResolution
                view.holder.setFixedSize(res.width, res.height)
            },
        )
        overlay()
    }
}
