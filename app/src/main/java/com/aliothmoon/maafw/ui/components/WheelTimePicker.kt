package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val HOUR_COUNT = 24
private const val MINUTE_COUNT = 60

/** 循环滚轮的虚拟圈数，起点放中间，两头实际滚不到 */
private const val LOOP_CYCLES = 1000

/** 离中心越远越淡 */
private const val FADE_DEPTH = 0.65f

/** 离中心越远越小 */
private const val SHRINK_DEPTH = 0.24f

/** 向中心收拢的比例，模拟滚筒透视 */
private const val GATHER_RATIO = 0.09f

/**
 * 24 小时制滚轮状态
 *
 * 构造参数只作为初始值，之后由滚轮与调用方驱动
 */
@Stable
class WheelTimePickerState(initialHour: Int, initialMinute: Int) {
    internal val startHour = initialHour.coerceIn(0, HOUR_COUNT - 1)
    internal val startMinute = initialMinute.coerceIn(0, MINUTE_COUNT - 1)

    var hour by mutableIntStateOf(startHour)
        internal set

    var minute by mutableIntStateOf(startMinute)
        internal set
}

@Composable
fun rememberWheelTimePickerState(
    initialHour: Int,
    initialMinute: Int,
): WheelTimePickerState = remember { WheelTimePickerState(initialHour, initialMinute) }

/**
 * 自绘时分滚轮，用来替代 M3 的 TimePicker / TimeInput
 *
 * M3 表盘与键盘输入在不同 Android 版本上表现不一致，这里只依赖 foundation 的滚动与吸附
 *
 * [rows] 需为奇数，偶数会被下调一格；极矮对话框可以只留选中行
 */
@Composable
fun WheelTimePicker(
    state: WheelTimePickerState,
    modifier: Modifier = Modifier,
    rows: Int = 5,
    itemHeight: Dp = 48.dp,
) {
    val visibleRows = rows.coerceAtLeast(1).let { if (it % 2 == 0) it - 1 else it }
    val colors = MaterialTheme.colorScheme
    val selectionShape = RoundedCornerShape(MaaTheme.style.radii.card)
    val digitStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        // 等宽数字，滚动时字形不会左右抖
        fontFeatureSettings = "tnum",
    )

    Box(
        modifier = modifier.height(itemHeight * visibleRows),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.widthIn(max = 304.dp).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // 选中行：居中铺一条渐变胶囊，滚轮在其上滚动
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(selectionShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.primaryContainer.copy(alpha = 0.65f),
                                colors.primaryContainer,
                                colors.primaryContainer.copy(alpha = 0.65f),
                            )
                        )
                    )
                    .border(1.dp, colors.primary.copy(alpha = 0.12f), selectionShape)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaaDesignTokens.Spacing.md)
                    .fadeVerticalEdges(edgeFraction = 0.75f / visibleRows),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelColumn(
                    count = HOUR_COUNT,
                    initialIndex = state.startHour,
                    selectedValue = state.hour,
                    semanticLabel = stringResource(R.string.time_picker_hour),
                    onSelect = { state.hour = it },
                    rows = visibleRows,
                    itemHeight = itemHeight,
                    digitStyle = digitStyle,
                    modifier = Modifier.weight(1f),
                )
                Column(
                    modifier = Modifier.width(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(2) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(colors.primary.copy(alpha = 0.7f), CircleShape)
                        )
                    }
                }
                WheelColumn(
                    count = MINUTE_COUNT,
                    initialIndex = state.startMinute,
                    selectedValue = state.minute,
                    semanticLabel = stringResource(R.string.time_picker_minute),
                    onSelect = { state.minute = it },
                    rows = visibleRows,
                    itemHeight = itemHeight,
                    digitStyle = digitStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 单列循环滚轮，虚拟项按 [count] 取模，首尾相接 */
@Composable
private fun WheelColumn(
    count: Int,
    initialIndex: Int,
    selectedValue: Int,
    semanticLabel: String,
    onSelect: (Int) -> Unit,
    rows: Int,
    itemHeight: Dp,
    digitStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val startIndex = count * (LOOP_CYCLES / 2) + initialIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val edgeRows = (rows / 2).toFloat()

    // 取中心线最近的一项，坐标系与 viewport 一致，不受内边距口径影响
    val centered by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
        }
    }

    // rows 变化会改变内边距，先按当前选中值重新对中，避免布局漂移被当成新选择
    LaunchedEffect(listState, rows) {
        listState.scrollToItem(count * (LOOP_CYCLES / 2) + selectedValue)

        // 滚动过程中持续上报，避免未停稳就点确定拿到旧值
        var first = true
        snapshotFlow { centered }
            .filterNotNull()
            .map { it % count }
            .distinctUntilChanged()
            .collect { index ->
                onSelect(index)
                if (!first) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                first = false
            }
    }

    LazyColumn(
        modifier = modifier
            .height(itemHeight * rows),
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(vertical = itemHeight * (rows / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count * LOOP_CYCLES, key = { it }) { index ->
            val isSelected = (centered ?: startIndex) == index
            val wheelValue = (index % count).toString().padStart(2, '0')
            val digitColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(durationMillis = 120),
                label = "wheelDigitColor",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .semantics {
                        selected = isSelected
                        contentDescription = "$semanticLabel $wheelValue"
                    }
                    // 变形放在绘制阶段按像素距离连续插值，不触发重组
                    .graphicsLayer {
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                        // 以行为单位的有符号距离，布局未完成时按初始位置估算
                        val offsetRows = if (item != null && item.size > 0) {
                            val viewCenter =
                                (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            (item.offset + item.size / 2f - viewCenter) / item.size
                        } else {
                            (index - startIndex).toFloat()
                        }
                        val depth = (abs(offsetRows) / edgeRows.coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        alpha = 1f - FADE_DEPTH * depth
                        val shrink = 1f - SHRINK_DEPTH * depth
                        scaleX = shrink
                        scaleY = shrink
                        translationY = -offsetRows * itemHeight.toPx() * GATHER_RATIO
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { scope.launch { listState.animateScrollToItem(index) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = wheelValue,
                    style = digitStyle,
                    color = digitColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 上下边缘做真 alpha 遮罩淡出，与所在背景色无关 */
private fun Modifier.fadeVerticalEdges(edgeFraction: Float): Modifier {
    val edge = edgeFraction.coerceIn(0f, 0.5f)
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    edge to Color.Black,
                    1f - edge to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
