package com.aliothmoon.maafw.ui.components

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.aliothmoon.maafw.runner.DisplayResolution
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `setFixedSize` 必须延后一拍
 *
 * SurfaceView 在两个宿主之间搬家时会走一轮 detach/attach，
 * 在 `surfaceCreated` 里同步调 `setFixedSize` 会被这轮吞掉：
 * `surfaceChanged` 只报一次布局尺寸就不再报，尺寸门槛永远过不去，画面一直是黑的
 */
private const val FIXED_SIZE_DELAY_MS = 50L

/**
 * 虚拟屏预览面
 *
 * 只在 Surface 尺寸等于虚拟屏尺寸时才上报：`setFixedSize` 是异步的，
 * 提前把还是布局尺寸的 Surface 交出去，画面会按错误比例贴上来
 *
 * [onSurfaceCreated] 与 [onSurfaceAvailable] 差着一整轮 `setFixedSize`，不能合并：
 * 前者是「画布有了」，遮罩与画中画武装看它；后者是「尺寸对上了，能交给特权进程」
 *
 * 本组件不持有「已上报哪个 Surface」的状态，也不在离开组合时解绑——
 * 它整个活在 movableContent 里，搬家时这些状态会跟着一起动，
 * 判重与解绑都由外层（[com.aliothmoon.maafw.ui.tasks.rememberMovablePreview]）负责
 */
@Composable
fun MaaPreviewSurface(
    resolution: DisplayResolution,
    onSurfaceCreated: () -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    val currentResolution by rememberUpdatedState(resolution)
    val currentCreated by rememberUpdatedState(onSurfaceCreated)
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(resolution.aspectRatio)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.setFormat(PixelFormat.RGBA_8888)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                currentCreated()
                                scope.launch {
                                    delay(FIXED_SIZE_DELAY_MS)
                                    val res = currentResolution
                                    holder.setFixedSize(res.width, res.height)
                                }
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                val res = currentResolution
                                if (width == res.width && height == res.height) {
                                    currentAvailable(holder.surface)
                                }
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                currentDestroyed()
                            }
                        })
                    }
                },
            )
            overlay()
        }
    }
}
