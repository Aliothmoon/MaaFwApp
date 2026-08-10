package com.aliothmoon.maafw.overlay.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 屏保自成一套视觉，不套用 `Maa*` 组件
 *
 * 这一层的取值目标只有两个：把整屏压到最黑（省电、防烧屏），以及让误触做不成任何事
 * 所以配色是写死的纯黑加白色低透明度，不吃 colorScheme
 */
private object ScreenSaverDimens {
    /** 时钟；M3 的 displayLarge 只有 34sp，隔着一米看不清 */
    val ClockFontSize: TextUnit = 72.sp

    /** 解锁条：轨道、滑块与轨道内边距 */
    val TrackHeight: Dp = 60.dp
    val ThumbDiameter: Dp = 48.dp
    val TrackPadding: Dp = 6.dp

    /** 解锁条离底的距离；再低会压到手势条上 */
    val BarBottomInset: Dp = 56.dp

    /** 解锁条左右留白，同时也是它水平漂移的余量上限 */
    val BarSideInset: Dp = 32.dp
}

@Composable
fun ScreenSaverView(
    latestLog: StateFlow<String?>,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val log by latestLog.collectAsState()
    val batteryState = rememberBatteryState()
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 漂移范围以 px 存，与 offset { IntOffset } 的单位一致
    val maxOffsetXPx = with(density) { (configuration.screenWidthDp / 8).dp.roundToPx() }
    val maxOffsetYPx = with(density) { (configuration.screenHeightDp / 4).dp.roundToPx() }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    // 解锁条另算一份漂移：它锚在底部，垂直向上空间充足、向下受 BarBottomInset 限制，故上大下小
    val barDriftXPx = with(density) { MaaDesignTokens.Spacing.xl.roundToPx() }
    val barDriftUpPx = with(density) { 80.dp.roundToPx() }
    val barDriftDownPx = with(density) { 40.dp.roundToPx() }
    var barOffsetX by remember { mutableIntStateOf(0) }
    var barOffsetY by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(DRIFT_INTERVAL_MS)
            burnInOffsetX = if (maxOffsetXPx > 0) (-maxOffsetXPx..maxOffsetXPx).random() else 0
            burnInOffsetY = if (maxOffsetYPx > 0) (-maxOffsetYPx..maxOffsetYPx).random() else 0
            barOffsetX = (-barDriftXPx..barDriftXPx).random()
            barOffsetY = (-barDriftUpPx..barDriftDownPx).random()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(burnInOffsetX, burnInOffsetY) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            Text(
                text = currentTime.format(timeFormatter),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = ScreenSaverDimens.ClockFontSize,
                    fontWeight = FontWeight.Light,
                ),
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
            )

            Text(
                text = "${batteryState.level}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = if (batteryState.isCharging) 0.7f else 0.4f),
            )

            Text(
                text = log ?: stringResource(R.string.screensaver_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = ScreenSaverDimens.BarSideInset),
            )
        }

        SlideToUnlockBar(
            onUnlock = onUnlock,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(barOffsetX, barOffsetY) }
                .padding(bottom = ScreenSaverDimens.BarBottomInset)
                .padding(horizontal = ScreenSaverDimens.BarSideInset)
                .fillMaxWidth(),
        )
    }
}

/**
 * 只认横向拖拽的解锁条
 *
 * 不做成按钮：屏保盖着的时候口袋里的一次误触就该什么都不发生
 */
@Composable
private fun SlideToUnlockBar(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbDiameterPx = with(density) { ScreenSaverDimens.ThumbDiameter.toPx() }
    val trackPaddingPx = with(density) { ScreenSaverDimens.TrackPadding.toPx() }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val maxOffset = (trackWidthPx - thumbDiameterPx - trackPaddingPx * 2).coerceAtLeast(0f)

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val releaseAnim = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val displayOffset = if (isAnimating) releaseAnim.value else dragOffset
    val progress = if (maxOffset > 0f) (displayOffset / maxOffset).coerceIn(0f, 1f) else 0f

    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerPos by shimmerTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPos",
    )
    // 拖到越后面越不需要提示，扫光跟着淡出
    val shimmerAlpha = (1f - progress) * 0.28f

    Box(
        modifier = modifier
            .height(ScreenSaverDimens.TrackHeight)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.13f))
            .drawBehind {
                val sweepWidth = size.width * 0.42f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = shimmerAlpha),
                            Color.Transparent,
                        ),
                        startX = shimmerPos * size.width - sweepWidth,
                        endX = shimmerPos * size.width + sweepWidth,
                    ),
                )
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    if (!isAnimating && maxOffset > 0f) {
                        dragOffset = (dragOffset + delta).coerceIn(0f, maxOffset)
                    }
                },
                onDragStopped = { velocity ->
                    val unlocked = dragOffset >= maxOffset * UNLOCK_RATIO || velocity > UNLOCK_VELOCITY
                    if (!isAnimating && maxOffset > 0f) {
                        scope.launch {
                            isAnimating = true
                            releaseAnim.snapTo(dragOffset)
                            if (unlocked) {
                                releaseAnim.animateTo(maxOffset, tween(SNAP_DURATION_MS))
                            }
                            // 先复位再回调：onUnlock 会销毁这棵 Composition
                            dragOffset = 0f
                            if (unlocked) {
                                releaseAnim.snapTo(0f)
                            } else {
                                releaseAnim.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                            isAnimating = false
                            if (unlocked) onUnlock()
                        }
                    }
                },
            ),
    ) {
        Text(
            text = stringResource(R.string.screensaver_swipe_to_unlock),
            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 3.sp),
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.Center)
                // 比进度快一倍淡出：手指还没到中间，字就该让位给滑块
                .alpha((1f - progress * 2f).coerceIn(0f, 1f)),
        )

        Box(
            modifier = Modifier
                .padding(ScreenSaverDimens.TrackPadding)
                .size(ScreenSaverDimens.ThumbDiameter)
                .offset { IntOffset(displayOffset.toInt(), 0) }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
        }
    }
}

data class BatteryState(val level: Int = 100, val isCharging: Boolean = false)

/** 电量没有可订阅的 API，只有这条粘性广播 */
@Composable
private fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState()) }

    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // 先读粘性广播拿初值再注册，否则首帧只能显示默认的 100%
        context.registerReceiver(null, filter)?.let { state = it.toBatteryState() ?: state }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                intent.toBatteryState()?.let { state = it }
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/** scale 拿不到时返回 null：算出来的百分比会是负数，不如不更新 */
private fun Intent.toBatteryState(): BatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return BatteryState(
        level = level * 100 / scale,
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** 时钟刷新与防烧屏漂移共用这一拍；再密就是白耗电 */
private const val DRIFT_INTERVAL_MS = 30_000L
private const val SHIMMER_DURATION_MS = 2_400
private const val SNAP_DURATION_MS = 120

/** 拖过七成、或快速轻扫都算解锁——只认前者会让快扫的用户以为条卡住了 */
private const val UNLOCK_RATIO = 0.75f
private const val UNLOCK_VELOCITY = 1_000f
