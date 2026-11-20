package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val DefaultScrollbarThickness = 3.dp
private val DefaultMinThumbLength = 24.dp
private const val DefaultIdleAlpha = 0.28f
private const val DefaultActiveAlpha = 0.55f
private const val FadeRelaxDelayMs = 400L

/**
 * 为 `verticalScroll` 视口绘制右侧滚动指示条：仅作视觉提示，不拦截任何手势
 *
 * **必须挂在 `Modifier.verticalScroll(state)` 之前**：排在前面拿到的才是未平移的视口尺寸与坐标系，
 * 排在后面会跟着内容一起滚动并拿到总内容高度
 *
 * 只要内容超出视口即保持低不透明度常显（保证刚打开页面就能看出可滚动），
 * 滑动与惯性期间提亮，停稳片刻后回落；读取 `state.value` / `maxValue` 与动画值均在绘制阶段完成，
 * 滚动全程不触发重组
 */
@Composable
fun Modifier.maaVerticalScrollbar(
    state: ScrollState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    thickness: Dp = DefaultScrollbarThickness,
    minThumbLength: Dp = DefaultMinThumbLength,
    endPadding: Dp = MaaDesignTokens.Spacing.xs,
    verticalPadding: Dp = MaaDesignTokens.Spacing.xs,
    idleAlpha: Float = DefaultIdleAlpha,
    activeAlpha: Float = DefaultActiveAlpha,
): Modifier = this then MaaVerticalScrollbarElement(
    state = state,
    color = color,
    thickness = thickness,
    minThumbLength = minThumbLength,
    endPadding = endPadding,
    verticalPadding = verticalPadding,
    idleAlpha = idleAlpha,
    activeAlpha = activeAlpha,
)

internal data class ScrollbarThumbMetrics(
    val topPx: Float,
    val heightPx: Float,
)

internal fun computeVerticalScrollbarThumb(
    viewportHeightPx: Float,
    scrollOffsetPx: Int,
    maxScrollOffsetPx: Int,
    topPaddingPx: Float,
    bottomPaddingPx: Float,
    minThumbLengthPx: Float,
): ScrollbarThumbMetrics? {
    // ScrollState 首次测量前 maxValue 为 Int.MAX_VALUE，拦截掉避免首帧闪一下
    if (maxScrollOffsetPx <= 0 || maxScrollOffsetPx == Int.MAX_VALUE) return null
    if (viewportHeightPx <= 0f) return null
    val trackHeight = viewportHeightPx - topPaddingPx - bottomPaddingPx
    if (trackHeight <= 0f) return null

    val contentHeight = viewportHeightPx + maxScrollOffsetPx.toFloat()
    val rawThumbHeight = trackHeight * (viewportHeightPx / contentHeight)
    val effectiveMinThumb = minThumbLengthPx.coerceAtLeast(0f).coerceAtMost(trackHeight)
    val thumbHeight = rawThumbHeight.coerceIn(effectiveMinThumb, trackHeight)
    // 动态展开/收起子项导致 maxValue 瞬时收缩时，value 可能暂超上限，钳制在轨道内
    val scrollFraction = (scrollOffsetPx.toFloat() / maxScrollOffsetPx.toFloat()).coerceIn(0f, 1f)
    val thumbTop = topPaddingPx + scrollFraction * (trackHeight - thumbHeight)
    return ScrollbarThumbMetrics(topPx = thumbTop, heightPx = thumbHeight)
}

private data class MaaVerticalScrollbarElement(
    val state: ScrollState,
    val color: Color,
    val thickness: Dp,
    val minThumbLength: Dp,
    val endPadding: Dp,
    val verticalPadding: Dp,
    val idleAlpha: Float,
    val activeAlpha: Float,
) : ModifierNodeElement<MaaVerticalScrollbarNode>() {

    override fun create(): MaaVerticalScrollbarNode = MaaVerticalScrollbarNode(
        state = state,
        color = color,
        thickness = thickness,
        minThumbLength = minThumbLength,
        endPadding = endPadding,
        verticalPadding = verticalPadding,
        idleAlpha = idleAlpha,
        activeAlpha = activeAlpha,
    )

    override fun update(node: MaaVerticalScrollbarNode) {
        node.update(
            state = state,
            color = color,
            thickness = thickness,
            minThumbLength = minThumbLength,
            endPadding = endPadding,
            verticalPadding = verticalPadding,
            idleAlpha = idleAlpha,
            activeAlpha = activeAlpha,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "maaVerticalScrollbar"
        properties["thickness"] = thickness
        properties["endPadding"] = endPadding
        properties["verticalPadding"] = verticalPadding
    }
}

private class MaaVerticalScrollbarNode(
    private var state: ScrollState,
    private var color: Color,
    private var thickness: Dp,
    private var minThumbLength: Dp,
    private var endPadding: Dp,
    private var verticalPadding: Dp,
    private var idleAlpha: Float,
    private var activeAlpha: Float,
) : Modifier.Node(), DrawModifierNode {

    private var alphaAnim = Animatable(idleAlpha)
    private var observeJob: Job? = null

    override fun onAttach() {
        startObservingScroll()
    }

    override fun onDetach() {
        observeJob?.cancel()
        observeJob = null
        // 脱离时协程已随作用域取消，清掉中间值避免复用到下一个组件时残留高亮
        alphaAnim = Animatable(idleAlpha)
    }

    override fun onReset() {
        observeJob?.cancel()
        observeJob = null
        alphaAnim = Animatable(idleAlpha)
        super.onReset()
    }

    fun update(
        state: ScrollState,
        color: Color,
        thickness: Dp,
        minThumbLength: Dp,
        endPadding: Dp,
        verticalPadding: Dp,
        idleAlpha: Float,
        activeAlpha: Float,
    ) {
        val stateChanged = this.state !== state
        val alphaChanged = this.idleAlpha != idleAlpha || this.activeAlpha != activeAlpha
        val visualChanged = this.color != color ||
            this.thickness != thickness ||
            this.minThumbLength != minThumbLength ||
            this.endPadding != endPadding ||
            this.verticalPadding != verticalPadding ||
            stateChanged ||
            alphaChanged

        this.state = state
        this.color = color
        this.thickness = thickness
        this.minThumbLength = minThumbLength
        this.endPadding = endPadding
        this.verticalPadding = verticalPadding
        this.idleAlpha = idleAlpha
        this.activeAlpha = activeAlpha

        if ((stateChanged || alphaChanged) && isAttached) {
            startObservingScroll()
        }
        if (visualChanged) {
            invalidateDraw()
        }
    }

    private fun startObservingScroll() {
        observeJob?.cancel()
        observeJob = coroutineScope.launch {
            snapshotFlow { state.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (scrolling) {
                        alphaAnim.animateTo(activeAlpha, MaaMotion.enter(MaaMotion.DURATION_SHORT))
                    } else if (alphaAnim.value != idleAlpha) {
                        delay(FadeRelaxDelayMs)
                        alphaAnim.animateTo(idleAlpha, MaaMotion.exit(MaaMotion.DURATION_MEDIUM))
                    }
                }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val verticalPaddingPx = verticalPadding.toPx()
        val thumb = computeVerticalScrollbarThumb(
            viewportHeightPx = size.height,
            scrollOffsetPx = state.value,
            maxScrollOffsetPx = state.maxValue,
            topPaddingPx = verticalPaddingPx,
            bottomPaddingPx = verticalPaddingPx,
            minThumbLengthPx = minThumbLength.toPx(),
        ) ?: return

        val thicknessPx = thickness.toPx()
        val endPaddingPx = endPadding.toPx()
        val thumbLeft = when (layoutDirection) {
            LayoutDirection.Ltr -> size.width - endPaddingPx - thicknessPx
            LayoutDirection.Rtl -> endPaddingPx
        }
        val radius = thicknessPx / 2f
        drawRoundRect(
            color = color.copy(alpha = color.alpha * alphaAnim.value),
            topLeft = Offset(thumbLeft, thumb.topPx),
            size = Size(thicknessPx, thumb.heightPx),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}
