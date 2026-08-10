package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.i18n.localized

/** 未勾选任务的文案区淡化程度；Checkbox 与删除钮不跟着淡，否则点不准 */
private const val DisabledTaskAlpha = 0.55f

@Composable
internal fun TaskRow(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (task.enabled) 1f else DisabledTaskAlpha,
        animationSpec = MaaMotion.enter(),
        label = "taskContentAlpha",
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) MaaDesignTokens.Card.dragElevation else 0.dp,
        animationSpec = MaaMotion.enter(),
        label = "dragElevation",
    )
    MaaCard(
        modifier = modifier
            .shadow(elevation = dragElevation, shape = MaterialTheme.shapes.medium)
            .maaClickable(enabled = task.hasOptions, onClick = onClick),
        contentPadding = PaddingValues(
            horizontal = MaaDesignTokens.Spacing.xs,
            vertical = MaaDesignTokens.Spacing.xs,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = task.enabled,
                onCheckedChange = onToggle,
                enabled = !locked && !task.missingDefinition,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.effectiveEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                task.unavailableReason?.let {
                    MaaToneBadge(
                        text = it.localized(),
                        tone = MaaTone(
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.errorContainer,
                        ),
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
            }
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
            IconButton(onClick = onRemove, enabled = !locked) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.tasks_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
            IconButton(onClick = {}, modifier = dragHandleModifier, enabled = !locked) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = stringResource(R.string.tasks_drag_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
        }
    }
}
