package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.uniqueCopyLabel
import com.aliothmoon.maafw.session.SessionIntent
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaEmptyState
import com.aliothmoon.maafw.ui.components.MaaSelectableCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 任务列表与它的拖拽序；空列表时只显示空状态（添加入口在配置选择行） */
@Composable
internal fun TaskList(
    active: ResolvedRunConfiguration,
    locked: Boolean,
    onIntent: (SessionIntent) -> Unit,
    onRequestAddTasks: () -> Unit,
    onEditTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copySuffix = stringResource(R.string.tasks_copy_suffix)
    var renamingTask by remember { mutableStateOf<ResolvedConfiguredTask?>(null) }

    if (active.tasks.isEmpty()) {
        MaaEmptyState(
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

    LazyColumn(
        state = listState,
        modifier = modifier,
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
                    onDuplicate = {
                        val copyName = uniqueCopyLabel(task.label, copySuffix, active.tasks.map { it.label })
                        onIntent(SessionIntent.DuplicateTask(active.id, task.instanceId, copyName))
                    },
                    onRename = { renamingTask = task },
                    onClick = {
                        if (task.hasOptions) onEditTask(task.instanceId)
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    renamingTask?.let { task ->
        var name by remember(task.instanceId) {
            mutableStateOf(TextFieldValue(task.label, TextRange(0, task.label.length)))
        }
        AlertDialog(
            onDismissRequest = { renamingTask = null },
            title = { Text(stringResource(R.string.tasks_rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tasks_rename_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(SessionIntent.RenameTask(active.id, task.instanceId, name.text.trim()))
                    renamingTask = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingTask = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/**
 * 配置选择 +「添加任务」
 * 间距收紧；添加钮与配置卡同圆角 / 同描边厚度，高度拉齐成一组
 */
@Composable
internal fun ConfigurationSelectorRow(
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    logOpen: Boolean,
    onSelectConfig: () -> Unit,
    onAddTasks: () -> Unit,
    onToggleLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 固定高度而非 IntrinsicSize.Min：三件东西各有各的固有高度（卡按文字、按钮按 Material
    // 最小尺寸），交给固有高度对齐必然是最高的那个说了算，压不下来
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ConfigRowHeight),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 按钮的 48dp 最小触控区会把整行顶起来；这一行本来就贴着预览卡，让位给任务列表
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            ConfigurationSelectorCard(
                active = active,
                locked = locked,
                onClick = onSelectConfig,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            RowActionButton(
                onClick = onAddTasks,
                enabled = !locked && active != null,
                icon = MaaIcons.Add,
                label = stringResource(R.string.tasks_add_task),
            )
            // 不给图标：两个字比任何图标都说得清，且「日志 / 关闭」等宽，切换时按钮不跳
            RowActionButton(
                onClick = onToggleLog,
                enabled = true,
                icon = null,
                label = stringResource(if (logOpen) R.string.common_close else R.string.tasks_log),
                active = logOpen,
            )
        }
    }
}

/** 配置行三件套的统一高度：容得下一行 titleMedium，再低就压字了 */
private val ConfigRowHeight = 44.dp

/**
 * 配置行右侧的动作钮：primary 字色 + 中性描边（有框，不要 primary 色框）
 *
 * [active] 为 true 时描边也转 primary，用来表达「日志正开着」这种开关态
 * [icon] 为 null 即纯文字；有文字时不另给 contentDescription，读屏念的就是按钮上的字
 */
@Composable
private fun RowActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
        contentPadding = PaddingValues(horizontal = MaaDesignTokens.Spacing.md),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = scheme.primary,
            disabledContentColor = scheme.onSurface.copy(alpha = MaaDesignTokens.Alpha.disabledContent),
        ),
        border = BorderStroke(
            MaaDesignTokens.Separator.thickness,
            if (active) scheme.primary else scheme.outline,
        ),
        modifier = modifier.fillMaxHeight(),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
            Box(Modifier.size(MaaDesignTokens.Spacing.xs))
        }
        Text(text = label, maxLines = 1)
    }
}

@Composable
private fun ConfigurationSelectorCard(
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaaSelectableCard(
        selected = false,
        onClick = onClick,
        enabled = !locked,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            // 与底栏 Tab 同形态：裸 Icon + tint，不用带底色的 MaaIconBadge
            Icon(
                imageVector = Icons.Outlined.Layers,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
            // 只留配置名：任务数与有效数在下面的列表里一眼就能数，
            // 摆在这儿只是把这一行撑成两行高
            Text(
                text = active?.name ?: stringResource(R.string.config_select),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
        }
    }
}

