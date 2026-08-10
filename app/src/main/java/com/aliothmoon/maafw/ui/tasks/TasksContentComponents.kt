package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaIconBadge
import com.aliothmoon.maafw.ui.components.MaaSelectableCard
import com.aliothmoon.maafw.ui.components.maaClickable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 任务列表与它的拖拽序；空列表时只显示空状态 */
@Composable
internal fun TaskList(
    active: ResolvedRunConfiguration,
    locked: Boolean,
    onIntent: (SessionIntent) -> Unit,
    onRequestAddTasks: () -> Unit,
    onEditTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (active.tasks.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.AddTask,
            title = stringResource(R.string.tasks_empty_task_title),
            hint = stringResource(R.string.tasks_empty_task_hint),
            modifier = modifier,
            action = {
                FilledTonalButton(onClick = onRequestAddTasks, enabled = !locked) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                    )
                    Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
                    Text(stringResource(R.string.tasks_add_task))
                }
            },
        )
        return
    }

    // 拖拽用本地顺序，松手发 MoveTask；DataStore 回流后以权威顺序为准
    val canonicalIds = remember(active.tasks) { active.tasks.map { it.instanceId } }
    val canonicalState = rememberUpdatedState(canonicalIds)
    var pendingOrder by remember(active.id) { mutableStateOf<List<String>?>(null) }

    fun currentOrder(): List<String> {
        val canonical = canonicalState.value
        val pending = pendingOrder
        return if (pending != null && pending.toSet() == canonical.toSet()) pending else canonical
    }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        pendingOrder = currentOrder().toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    val tasksById = remember(active.tasks) { active.tasks.associateBy { it.instanceId } }

    Column(modifier = modifier) {
        TaskListHeader(locked = locked, onAdd = onRequestAddTasks)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            contentPadding = PaddingValues(bottom = MaaDesignTokens.Spacing.sm),
        ) {
            items(
                items = currentOrder(),
                key = { it },
                contentType = { "task" },
            ) { instanceId ->
                val task = tasksById[instanceId] ?: return@items
                ReorderableItem(reorderableState, key = instanceId) { isDragging ->
                    TaskRow(
                        task = task,
                        locked = locked,
                        isDragging = isDragging,
                        dragHandleModifier = Modifier.draggableHandle(
                            enabled = !locked,
                            onDragStopped = {
                                val target = currentOrder().indexOf(instanceId)
                                val origin = canonicalState.value.indexOf(instanceId)
                                if (target >= 0 && origin >= 0 && target != origin) {
                                    onIntent(SessionIntent.MoveTask(active.id, instanceId, target))
                                }
                            },
                        ),
                        onToggle = { enabled ->
                            onIntent(SessionIntent.ToggleTask(active.id, task.instanceId, enabled))
                        },
                        onRemove = {
                            onIntent(SessionIntent.RemoveTask(active.id, task.instanceId))
                        },
                        onClick = {
                            if (task.hasOptions) onEditTask(task.instanceId)
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListHeader(locked: Boolean, onAdd: () -> Unit) {
    val addTint = if (locked) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaDesignTokens.Alpha.disabledContent)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tasks_run_order),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(MaaTheme.style.radii.button))
                .maaClickable(enabled = !locked, onClick = onAdd)
                .padding(
                    horizontal = MaaDesignTokens.Spacing.xs,
                    vertical = MaaDesignTokens.Spacing.xxs,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = addTint,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
            Spacer(Modifier.width(MaaDesignTokens.Spacing.xxs))
            Text(
                text = stringResource(R.string.tasks_add_task),
                style = MaterialTheme.typography.labelLarge,
                color = addTint,
            )
        }
    }
}

@Composable
internal fun ConfigurationSelectorCard(
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    onClick: () -> Unit,
) {
    MaaSelectableCard(
        selected = false,
        onClick = onClick,
        enabled = !locked,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.md,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaIconBadge(
                icon = Icons.Outlined.Layers,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = active?.name ?: stringResource(R.string.config_select),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (active != null) {
                        pluralStringResource(
                            R.plurals.config_current_summary,
                            active.tasks.size,
                            active.tasks.size,
                            active.effectiveTaskCount,
                        )
                    } else {
                        stringResource(R.string.config_select_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MaaIconBadge(
                icon = Icons.Outlined.UnfoldMore,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                containerSize = MaaDesignTokens.IconContainer.sm,
                shape = CircleShape,
            )
        }
    }
}

@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MaaDesignTokens.Alpha.disabled),
            modifier = Modifier.size(MaaDesignTokens.IconSize.xl),
        )
        Spacer(Modifier.size(MaaDesignTokens.Spacing.md))
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(MaaDesignTokens.Spacing.xs))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.size(MaaDesignTokens.Spacing.lg))
            it()
        }
    }
}
