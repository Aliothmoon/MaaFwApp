package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.i18n.uiTextPlural
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaIconBadge
import com.aliothmoon.maafw.ui.components.MaaSelectableCard
import com.aliothmoon.maafw.ui.components.ResourceSelectorRow
import com.aliothmoon.maafw.ui.components.ResourceSwitchSheet
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * [previewContent] 由 AppRoot 创建并持有：全屏宿主必须在 pager 之外才能盖住底部 tab 栏，
 * 而 movableContent 要求两处调用点在同一棵组合树里，所以整份预览面的所有权提到了顶层
 * 为 null 表示画面已搬去全屏，这里显示占位
 */
@Composable
fun TasksScreen(
    state: SessionUiState,
    previewSurfaceReady: Boolean,
    previewContent: (@Composable () -> Unit)?,
    runLog: List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = state.projectState,
        animationSpec = MaaMotion.enter(),
        modifier = modifier,
    ) { projectState ->
        when (projectState) {
            is ProjectState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is ProjectState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.xl),
                    )
                    Text(stringResource(R.string.tasks_project_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) {
                        Text(stringResource(R.string.tasks_reload))
                    }
                }
            }

            is ProjectState.Ready -> TasksContent(
                state = state,
                previewSurfaceReady = previewSurfaceReady,
                previewContent = previewContent,
                runLog = runLog,
                onEnterFullscreen = onEnterFullscreen,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun TasksContent(
    state: SessionUiState,
    previewSurfaceReady: Boolean,
    previewContent: (@Composable () -> Unit)?,
    runLog: List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.configurationLocked
    val active = state.activeConfiguration

    var showAddTasks by rememberSaveable { mutableStateOf(false) }
    var editingTaskInstanceId by rememberSaveable { mutableStateOf<String?>(null) }

    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    var showResourceSheet by rememberSaveable { mutableStateOf(false) }
    var showRunLog by rememberSaveable { mutableStateOf(false) }
    val environment = state.environment
    val resourceCandidates = environment?.resourceCandidates.orEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // AppRoot 已消费系统栏 inset，内层 Scaffold 不再重复
            contentWindowInsets = WindowInsets(),
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaaDesignTokens.Spacing.lg),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    LivePreview(
                        resolution = state.previewResolution,
                        surfaceReady = previewSurfaceReady,
                        running = state.runner.phase.isBusy,
                        content = previewContent,
                        onEnterFullscreen = onEnterFullscreen,
                    )
                    if (resourceCandidates.isNotEmpty()) {
                        ResourceSelectorRow(
                            label = environment?.resource?.label
                                ?: stringResource(R.string.settings_not_selected),
                            enabled = !locked,
                            onClick = { showResourceSheet = true },
                        )
                    }
                    ConfigurationSelectorCard(
                        active = active,
                        locked = locked,
                        onClick = { showConfigSheet = true },
                    )

                    if (active == null) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                            title = stringResource(R.string.tasks_empty_config_title),
                            hint = stringResource(R.string.tasks_empty_config_hint),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TaskList(
                            active = active,
                            locked = locked,
                            onIntent = onIntent,
                            onRequestAddTasks = { showAddTasks = true },
                            onEditTask = { editingTaskInstanceId = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(MaaDesignTokens.Spacing.xs))
                RunnerToggleButton(
                    state = state,
                    onIntent = onIntent,
                    onOpenRunLog = { showRunLog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showRunLog) {
        RunLogSheet(
            entries = runLog,
            onClear = { onIntent(SessionIntent.ClearRunLog) },
            onDismiss = { showRunLog = false },
        )
    }

    if (showResourceSheet && resourceCandidates.isNotEmpty()) {
        ResourceSwitchSheet(
            candidates = resourceCandidates,
            currentName = environment?.resource?.name,
            onSelect = { onIntent(SessionIntent.SelectResource(it)) },
            onDismiss = { showResourceSheet = false },
        )
    }

    if (showAddTasks && active != null) {
        AddTasksSheet(
            catalog = state.taskCatalog,
            locked = locked,
            onConfirm = { orderedNames ->
                onIntent(SessionIntent.ConfirmAddTasks(active.id, orderedNames))
                showAddTasks = false
            },
            onDismiss = { showAddTasks = false },
        )
    }

    val editingTask = editingTaskInstanceId?.let { id -> active?.tasks?.firstOrNull { it.instanceId == id } }
    if (editingTask != null && active != null) {
        TaskOptionSheet(
            task = editingTask,
            locked = locked,
            onSetOption = { optionName, value ->
                onIntent(SessionIntent.SetTaskOption(active.id, editingTask.instanceId, optionName, value))
            },
            onDismiss = { editingTaskInstanceId = null },
        )
    }

    if (showConfigSheet) {
        ConfigurationSheet(
            state = state,
            templates = (state.projectState as? ProjectState.Ready)?.definition?.templates.orEmpty(),
            locked = locked,
            onSelect = { id ->
                showConfigSheet = false
                onIntent(SessionIntent.SelectConfiguration(id))
            },
            onDuplicate = { id, name -> onIntent(SessionIntent.DuplicateConfiguration(id, name)) },
            onRename = { id, name -> onIntent(SessionIntent.RenameConfiguration(id, name)) },
            onDelete = { onIntent(SessionIntent.DeleteConfiguration(it)) },
            onCreateEmpty = { name ->
                showConfigSheet = false
                onIntent(SessionIntent.CreateConfiguration(name))
            },
            onCreateFromTemplate = { templateName, configurationName, taskNames ->
                showConfigSheet = false
                onIntent(SessionIntent.CreateFromTemplate(templateName, configurationName, taskNames))
            },
            onDismiss = { showConfigSheet = false },
        )
    }
}

/** 任务列表与它的拖拽序；空列表时只显示空状态 */
@Composable
private fun TaskList(
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
        ) {
            items(currentOrder(), key = { it }) { instanceId ->
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
                .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.button))
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
private fun ConfigurationSelectorCard(
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
                        uiTextPlural(
                            R.plurals.config_current_summary,
                            active.tasks.size,
                            active.tasks.size,
                            active.effectiveTaskCount,
                        ).asString()
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
private fun EmptyState(
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
