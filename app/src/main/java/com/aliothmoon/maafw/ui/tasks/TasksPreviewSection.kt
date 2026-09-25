package com.aliothmoon.maafw.ui.tasks

// 与 material3.Surface 同名，用别名区分
import android.view.Surface as PlatformSurface
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.privileged.WatchdogState
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.runner.PreviewTouchMarker
import com.aliothmoon.maafw.session.PreviewTouchAction
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaPreviewSurface
import com.aliothmoon.maafw.ui.components.MaaTouchOverlay
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.pip.LocalIsInPip
import kotlin.math.roundToInt

/**
 * 预览面做成 movableContent：在内嵌卡片与全屏宿主之间搬家时复用同一份组合状态
 *
 * 「已上报哪个 Surface」的判重状态放在 movableContent **外层**：
 * SurfaceView 搬家时必然走一轮 detach/attach，surfaceDestroyed 与 surfaceCreated 成对触发，
 * 判重状态若放在里面会跟着一起搬，拿不准新旧 Surface 的对应关系
 *
 * 闭包里读到的值必须走 rememberUpdatedState：movableContentOf 只创建一次，直接捕获会永远停在首帧
 */
@Composable
internal fun rememberMovablePreview(
    resolution: DisplayResolution,
    /** 传取值而不是值：一次滑动几十个触点，在 AppRoot 那层读会把整棵树按触摸频率重组 */
    markers: () -> List<PreviewTouchMarker>,
    fps: () -> Float?,
    onSurfaceCreated: () -> Unit,
    onSurfaceAvailable: (PlatformSurface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
): @Composable () -> Unit {
    val currentResolution by rememberUpdatedState(resolution)
    val currentMarkers by rememberUpdatedState(markers)
    val currentFps by rememberUpdatedState(fps)
    val currentCreated by rememberUpdatedState(onSurfaceCreated)
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    var lastSentSurface by remember { mutableStateOf<PlatformSurface?>(null) }
    return remember {
        movableContentOf {
            MaaPreviewSurface(
                resolution = currentResolution,
                onSurfaceCreated = { currentCreated() },
                onSurfaceAvailable = { surface ->
                    // surfaceChanged 会重复触发，同一个 Surface 不重复跨进程上报
                    if (lastSentSurface != surface) {
                        lastSentSurface = surface
                        currentAvailable(surface)
                    }
                },
                onSurfaceDestroyed = {
                    lastSentSurface = null
                    currentDestroyed()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 小窗内不画触摸轨迹：画面已缩到巴掌大，轨迹只会糊住画面（对齐 MaaMeow）
                if (!LocalIsInPip.current) {
                    MaaTouchOverlay(
                        markers = currentMarkers(),
                        resolution = currentResolution,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                currentFps()?.let { value ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            text = "${value.roundToInt()} FPS",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
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
    watchdogState: WatchdogState,
    content: (@Composable () -> Unit)?,
    onEnterFullscreen: () -> Unit,
    // 高度受限的宿主区域，由调用方用 weight 给出；卡片在其内按预览分辨率等比缩到最大并居中，
    // 横屏时不再整幅吃满宽度挤掉任务列表（对齐 MaaMeow VirtualDisplayPreview 的缩放）
    modifier: Modifier = Modifier,
    /** 卡片在 window 中的位置，给画中画的进入动画用 */
    onBoundsChanged: ((Rect?) -> Unit)? = null,
) {
    // 尺寸靠 aspectRatio 算而不是 BoxWithConstraints：后者是 SubcomposeLayout，
    // 测量期的首次组合撞上 movableContent 搬家会拿到已停用的节点（Apply is called on
    // deactivated node），翻页动画强制 remeasure 时必崩。matchHeightConstraintsFirst
    // 先按高度定、放不下再回退按宽度，与原先那个分支等价
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val aspect = resolution?.aspectRatio ?: (16f / 9f)
        val cardModifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)
        val boundsReporting = Modifier.onGloballyPositioned {
            onBoundsChanged?.invoke(it.boundsInWindow().let { rect ->
                Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            })
        }
        if (content == null) {
            MaaCardSurface(modifier = cardModifier.then(boundsReporting)) { LivePreviewIdleArt() }
            return@Box
        }
        MaaCardSurface(modifier = cardModifier.then(boundsReporting).maaClickable(onClick = onEnterFullscreen)) {
            Box(Modifier.fillMaxSize()) {
                content()
                PreviewStatusMask(surfaceReady = surfaceReady, running = running)
                WatchdogStatusBadge(
                    state = watchdogState,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(MaaDesignTokens.Spacing.sm),
                )
            }
        }
    }
}
@Composable
private fun WatchdogStatusBadge(state: WatchdogState, modifier: Modifier = Modifier) {
    // 着色对齐 MaaMeow 预览小窗右上角徽标；两种坏结局同为红，但文字要分得开——
    // 「已停止」去看应用是不是被杀了，「已离屏」去改运行模式，指向不同
    val (dotColor, label) = when (state) {
        WatchdogState.WATCHING ->
            Color(0xFF4CAF50) to stringResource(R.string.virtual_display_game_running)
        WatchdogState.APP_DIED ->
            Color(0xFFF44336) to stringResource(R.string.virtual_display_game_stopped)
        WatchdogState.DISPLAY_DRIFT ->
            Color(0xFFF44336) to stringResource(R.string.virtual_display_app_drifted)
        WatchdogState.IDLE ->
            Color(0xFF9E9E9E) to stringResource(R.string.virtual_display_idle)
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(MaaDesignTokens.CornerRadius.button))
            .padding(horizontal = MaaDesignTokens.Spacing.sm, vertical = MaaDesignTokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(MaaDesignTokens.IconSize.dotMd).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/**
 * 全屏宿主；与内嵌卡片共用同一份 movableContent
 *
 * 必须挂在 AppRoot 顶层而不是 pager 里，否则盖不住底部 tab 栏
 * 进出时接管系统栏与屏幕方向，退出时一律还原成进来前的值
 */
@Composable
internal fun FullscreenPreview(
    resolution: DisplayResolution,
    onExit: () -> Unit,
    onTouch: (x: Int, y: Int, action: PreviewTouchAction, contact: Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current.findActivity()

    DisposableEffect(activity) {
        val controller = activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 虚拟屏是横的，竖着看只有中间一条；退出时还原用户原本的方向设置
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        if (activity?.resources?.configuration?.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { if (original != null) activity.requestedOrientation = original }
    }

    BackHandler(onBack = onExit)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .previewTouchInput(resolution, onTouch),
        contentAlignment = Alignment.Center,
    ) {
        content()
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MaaDesignTokens.Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.tasks_preview_exit_fullscreen),
                tint = Color.White,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
        }
    }
}

/**
 * 逐个 pointer 上报，多指同时按下各走各的 contact
 *
 * 不走手势识别器：它们只跟一根手指，且要等 touch slop 才认，这里要的是每次位移原样透出去
 */
private fun Modifier.previewTouchInput(
    resolution: DisplayResolution,
    onTouch: (x: Int, y: Int, action: PreviewTouchAction, contact: Int) -> Unit,
): Modifier = pointerInput(resolution) {
    val slots = PreviewPointerSlots()
    try {
        awaitPointerEventScope {
            while (true) {
                for (change in awaitPointerEvent().changes) {
                    val pointerId = change.id.value
                    val down = change.changedToDownIgnoreConsumed()
                    val up = change.changedToUpIgnoreConsumed()
                    if (!down && !up && !change.positionChangedIgnoreConsumed()) continue

                    val point = viewToVirtualDisplay(change.position, size, resolution)
                    val contact = when {
                        // 黑边上的按下没有对应像素，丢掉；已按下的手指拖出画面仍要跟
                        down -> if (point.inside) slots.acquire(pointerId) else -1
                        up -> slots.release(pointerId)
                        else -> slots.indexOf(pointerId)
                    }
                    if (contact < 0) continue
                    val action = when {
                        down -> PreviewTouchAction.Down
                        up -> PreviewTouchAction.Up
                        else -> PreviewTouchAction.Move
                    }
                    slots.remember(contact, point.offset.x, point.offset.y)
                    onTouch(point.offset.x, point.offset.y, action, contact)
                    change.consume()
                }
            }
        }
    } finally {
        // 退出预览时仍按着的手指逐个抬起，不发整体 CANCEL：远端那张槽位表与 MaaFramework
        // 的注入共用，整体取消会把它正在做的手势一并丢掉
        slots.releaseHeld { contact, x, y -> onTouch(x, y, PreviewTouchAction.Up, contact) }
    }
}

/** [offset] 已钳进虚拟屏范围；[inside] 是钳之前落没落在画面上 */
private data class DisplayPoint(val offset: IntOffset, val inside: Boolean)

/**
 * 把手指位置换算到虚拟屏坐标
 *
 * 越界钳回边缘而不是丢掉：手指拖出画面后抬起，那条 up 也得送达，否则远端以为它还按着
 */
private fun viewToVirtualDisplay(
    view: Offset,
    viewSize: IntSize,
    resolution: DisplayResolution,
): DisplayPoint {
    val scale = minOf(
        viewSize.width / resolution.width.toFloat(),
        viewSize.height / resolution.height.toFloat(),
    )
    val offsetX = (viewSize.width - resolution.width * scale) / 2f
    val offsetY = (viewSize.height - resolution.height * scale) / 2f
    val vx = ((view.x - offsetX) / scale).toInt()
    val vy = ((view.y - offsetY) / scale).toInt()
    return DisplayPoint(
        offset = IntOffset(
            vx.coerceIn(0, resolution.width - 1),
            vy.coerceIn(0, resolution.height - 1),
        ),
        inside = vx in 0 until resolution.width && vy in 0 until resolution.height,
    )
}

/** Compose 的 LocalContext 可能是 ContextWrapper，逐层剥到 Activity */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
