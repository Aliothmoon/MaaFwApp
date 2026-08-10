package com.aliothmoon.maafw.ui.tasks

// 与 material3.Surface 同名，用别名区分
import android.view.Surface as PlatformSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.runner.PreviewTouchMarker
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaPreviewSurface
import com.aliothmoon.maafw.ui.components.MaaTouchOverlay
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 预览面做成 movableContent：在内嵌卡片与全屏宿主之间搬家时复用同一个 SurfaceView
 * 重建会销毁 Surface，得跟特权进程重新握手一次，画面要黑一下
 *
 * 闭包里读到的值必须走 rememberUpdatedState：movableContentOf 只创建一次，直接捕获会永远停在首帧
 */
@Composable
internal fun rememberMovablePreview(
    resolution: DisplayResolution,
    markers: List<PreviewTouchMarker>,
    onSurfaceAvailable: (PlatformSurface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
): @Composable () -> Unit {
    val currentResolution by rememberUpdatedState(resolution)
    val currentMarkers by rememberUpdatedState(markers)
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    return remember {
        movableContentOf {
            MaaPreviewSurface(
                resolution = currentResolution,
                onSurfaceAvailable = { currentAvailable(it) },
                onSurfaceDestroyed = { currentDestroyed() },
                modifier = Modifier.fillMaxSize(),
            ) {
                MaaTouchOverlay(
                    markers = currentMarkers,
                    resolution = currentResolution,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 虚拟屏实时预览的内嵌宿主；画面本身不进 RunnerState/RunnerEvent（docs/android-ui-contract.md §10）
 * [content] 为 null 表示项目未就绪或画面已搬去全屏，两种情况都显示占位
 */
@Composable
internal fun LivePreview(
    resolution: DisplayResolution?,
    surfaceReady: Boolean,
    running: Boolean,
    content: (@Composable () -> Unit)?,
    onEnterFullscreen: () -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(top = MaaDesignTokens.Spacing.md)
        .aspectRatio(resolution?.aspectRatio ?: (16f / 9f))
    if (content == null) {
        MaaCardSurface(modifier = cardModifier) { LivePreviewIdleArt() }
        return
    }
    MaaCardSurface(modifier = cardModifier.maaClickable(onClick = onEnterFullscreen)) {
        Box(Modifier.fillMaxSize()) {
            content()
            PreviewStatusMask(surfaceReady = surfaceReady, running = running)
        }
    }
}

/** 全屏宿主；与内嵌卡片共用同一份 movableContent，点任意处退出 */
@Composable
internal fun FullscreenPreview(
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .maaClickable(onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 画面还没上来时盖一层说明，免得黑屏看着像坏了 */
@Composable
private fun PreviewStatusMask(surfaceReady: Boolean, running: Boolean) {
    val text = when {
        !surfaceReady -> stringResource(R.string.tasks_preview_waiting_surface)
        !running -> stringResource(R.string.tasks_preview_idle)
        else -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LivePreviewIdleArt() {
    Box(contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.OndemandVideo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = MaaDesignTokens.Alpha.disabledContent),
                modifier = Modifier.size(MaaDesignTokens.IconSize.lg),
            )
            Text(
                text = stringResource(R.string.tasks_live_preview),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = MaaDesignTokens.Alpha.disabledContent),
            )
        }
    }
}
