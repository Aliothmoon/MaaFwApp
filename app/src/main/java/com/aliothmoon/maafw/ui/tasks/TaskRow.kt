package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaSkipReason
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.MaaCheckbox
import kotlin.math.max

/** 未勾选任务的文案区淡化程度；Checkbox 与删除钮不跟着淡，否则点不准 */
private const val DisabledTaskAlpha = 0.55f

/**
 * 紧凑动作图标：32dp 触控区 + 16dp 图标，比 IconButton 的 48dp 省两档
 *
 * 行尾四个（rename/copy/delete/拖拽）都用它，图标间距才匀；框比图标只富余 8dp，
 * 再大就把标题挤没了
 *
 * [onClick] 为 null 时不接点击——拖拽把手的手势在 [modifier] 上，套一层空 onClick
 * 会把长按之外的 tap 吞掉
 */
@Composable
private fun CompactActionIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else MaaDesignTokens.Alpha.disabledContent),
            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
        )
    }
}

@Composable
internal fun TaskRow(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    isDragging: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val contentAlpha by animateFloatAsState(
        // 跳过的任务勾没勾都跑不了，不按勾选调淡
        targetValue = if (task.enabled || task.skipped) 1f else DisabledTaskAlpha,
        animationSpec = MaaMotion.enter(),
        label = "taskContentAlpha",
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) MaaTheme.style.dragElevation else 0.dp,
        animationSpec = MaaMotion.enter(),
        label = "dragElevation",
    )
    MaaCard(
        modifier = modifier
            .shadow(elevation = dragElevation, shape = MaterialTheme.shapes.medium)
            .maaClickable(enabled = task.hasOptions, shape = MaterialTheme.shapes.medium, onClick = onClick),
        contentPadding = PaddingValues(
            horizontal = MaaDesignTokens.Spacing.xs,
            vertical = MaaDesignTokens.Spacing.xs,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (task.unavailableReason != null) Modifier.trimBottomSlack(MaaDesignTokens.Spacing.md) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaaCheckbox(
                checked = task.checkedForDisplay,
                onCheckedChange = onToggle,
                enabled = !locked && task.toggleable,
                skipped = task.skipped,
            )
            MaaPiIcon(
                path = task.icon,
                size = MaaDesignTokens.IconSize.md,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = MaaDesignTokens.Spacing.sm)
                    .alpha(contentAlpha),
            )
            Text(
                text = task.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.effectiveEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha)
                    .markLabelBottom(),
            )
            if (task.hasOptions) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(MaaDesignTokens.IconSize.md)
                        .alpha(contentAlpha),
                )
            }
            CompactActionIcon(
                icon = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.tasks_rename),
                enabled = !locked,
                onClick = onRename,
            )
            CompactActionIcon(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.tasks_duplicate),
                enabled = !locked,
                onClick = onDuplicate,
            )
            CompactActionIcon(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.tasks_remove),
                enabled = !locked,
                onClick = onRemove,
            )
            // 不用 IconButton：它内部写死原生 ripple()，不走主题里的 MaaPressIndication，
            // 按下反馈会借宿主视图池串到别的组件上；48dp 的框也比旁边三个宽出一截
            CompactActionIcon(
                icon = Icons.Outlined.DragIndicator,
                contentDescription = stringResource(R.string.tasks_drag_reorder),
                enabled = !locked,
                modifier = dragHandleModifier,
            )
        }
        task.unavailableReason?.let {
            // 页脚起点跟勾选框之后的内容列对齐：勾选框正好占一格最小触控尺寸
            Column(
                modifier = Modifier.padding(
                    start = LocalMinimumInteractiveComponentSize.current,
                    end = MaaDesignTokens.Spacing.sm,
                ),
            ) {
                HorizontalDivider(
                    thickness = MaaDesignTokens.Separator.thickness,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                MaaSkipReason(
                    reason = it.asString(),
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs, bottom = MaaDesignTokens.Spacing.sm),
                )
            }
        }
    }
}

private val LabelBottom = HorizontalAlignmentLine(::max)

private fun Modifier.markLabelBottom(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(LabelBottom to placeable.height)) { placeable.place(0, 0) }
}

/** 48dp 触控区把 Row 撑得比标题高，收掉标题下的空白（至多 [limit]）让页脚贴上来；标题折行撑满时不收，免得压字 */
private fun Modifier.trimBottomSlack(limit: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val labelBottom = placeable[LabelBottom]
    val slack = if (labelBottom == AlignmentLine.Unspecified) 0 else placeable.height - labelBottom
    layout(placeable.width, placeable.height - slack.coerceIn(0, limit.roundToPx())) { placeable.place(0, 0) }
}
