package com.aliothmoon.maafw.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.uniqueCopyLabel
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.i18n.asUiText
import com.aliothmoon.maafw.ui.tasks.setPresent
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 悬浮窗任务页：左窄列表 + 右平面编辑
 *
 * 不套任务页内容卡 / MaaButton / sheet 密度
 */
@Composable
internal fun OverlayTaskSplit(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.configurationLocked
    val active = state.activeConfiguration
    var configMode by rememberSaveable { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val taskIds = remember(active?.tasks) { active?.tasks?.map { it.instanceId }.orEmpty() }
    LaunchedEffect(active?.id, taskIds) {
        if (selectedId !in taskIds) selectedId = taskIds.firstOrNull()
    }
    LaunchedEffect(editMode) {
        if (!editMode) adding = false
    }

    val selected = active?.tasks?.firstOrNull { it.instanceId == selectedId }

    Row(modifier = modifier.fillMaxSize()) {
        OverlayTaskListPane(
            active = active,
            locked = locked,
            configMode = configMode,
            editMode = editMode,
            adding = adding,
            selectedId = selectedId,
            onConfigMode = {
                configMode = !configMode
                if (configMode) {
                    editMode = false
                    adding = false
                }
            },
            onEditMode = {
                editMode = !editMode
                if (editMode) configMode = false else adding = false
            },
            onAdding = {
                if (!editMode) {
                    editMode = true
                    configMode = false
                }
                adding = !adding
            },
            onSelect = { selectedId = it },
            onIntent = onIntent,
            modifier = Modifier
                .fillMaxHeight()
                .width(MaaDesignTokens.Overlay.leftMax),
        )
        Spacer(Modifier.width(MaaDesignTokens.Overlay.gap))
        OverlayTaskDetailPane(
            state = state,
            active = active,
            locked = locked,
            configMode = configMode,
            editMode = editMode,
            adding = adding,
            selected = selected,
            onExitAdding = { adding = false },
            onIntent = onIntent,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
        )
    }
}

@Composable
private fun OverlayTaskListPane(
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    configMode: Boolean,
    editMode: Boolean,
    adding: Boolean,
    selectedId: String?,
    onConfigMode: () -> Unit,
    onEditMode: () -> Unit,
    onAdding: () -> Unit,
    onSelect: (String) -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        OverlayModeRow(
            selected = configMode,
            enabled = true,
            icon = if (configMode) Icons.Outlined.Check else Icons.Outlined.Layers,
            label = stringResource(
                if (configMode) R.string.overlay_edit_done else R.string.overlay_manage_config,
            ),
            onClick = onConfigMode,
        )
        OverlayModeRow(
            selected = editMode,
            enabled = !locked,
            icon = if (editMode) Icons.Outlined.Check else Icons.Outlined.Edit,
            label = stringResource(
                if (editMode) R.string.overlay_edit_done else R.string.overlay_edit_tasks,
            ),
            onClick = onEditMode,
        )
        if (editMode) {
            OverlayModeRow(
                selected = adding,
                enabled = !locked && active != null,
                icon = Icons.Outlined.Add,
                label = stringResource(R.string.tasks_add_task),
                onClick = onAdding,
            )
        }
        if (active == null) {
            Text(
                text = stringResource(R.string.tasks_empty_config_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OverlayCompactTaskList(
                active = active,
                locked = locked,
                selectedId = selectedId,
                onSelect = onSelect,
                onIntent = onIntent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverlayCompactTaskList(
    active: ResolvedRunConfiguration,
    locked: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (active.tasks.isEmpty()) {
        Text(
            text = stringResource(R.string.tasks_empty_task_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
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
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxsLg),
        contentPadding = PaddingValues(bottom = MaaDesignTokens.Spacing.xs),
    ) {
        items(currentOrder(), key = { it }) { instanceId ->
            val task = tasksById[instanceId] ?: return@items
            ReorderableItem(reorderableState, key = instanceId) {
                OverlayTile(
                    selected = instanceId == selectedId,
                    onClick = { onSelect(instanceId) },
                    modifier = Modifier.longPressDraggableHandle(
                        enabled = !locked,
                        onDragStopped = {
                            val target = currentOrder().indexOf(instanceId)
                            val origin = canonicalState.value.indexOf(instanceId)
                            if (target >= 0 && origin >= 0 && target != origin) {
                                onIntent(SessionIntent.MoveTask(active.id, instanceId, target))
                            }
                        },
                    ),
                ) {
                    OverlayCheckbox(
                        checked = task.enabled,
                        onCheckedChange = { enabled ->
                            onIntent(SessionIntent.ToggleTask(active.id, task.instanceId, enabled))
                        },
                        enabled = !locked && !task.missingDefinition,
                    )
                    Text(
                        text = task.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (instanceId == selectedId) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayTaskDetailPane(
    state: SessionUiState,
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    configMode: Boolean,
    editMode: Boolean,
    adding: Boolean,
    selected: ResolvedConfiguredTask?,
    onExitAdding: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            configMode -> OverlayConfigPane(state = state, locked = locked, onIntent = onIntent)

            editMode && adding -> OverlayAddPane(
                catalog = state.taskCatalog,
                locked = locked,
                onConfirm = { names ->
                    active?.let { onIntent(SessionIntent.ConfirmAddTasks(it.id, names)) }
                    onExitAdding()
                },
            )

            editMode && selected != null && active != null -> OverlayTaskManagePane(
                active = active,
                task = selected,
                locked = locked,
                onIntent = onIntent,
            )

            editMode -> OverlayEmptyHint(
                title = stringResource(R.string.overlay_pane_edit_empty_title),
                lines = listOf(
                    stringResource(R.string.overlay_pane_edit_empty_hint),
                    stringResource(R.string.overlay_pane_edit_add_hint),
                ),
            )

            selected != null && active != null -> {
                key(selected.instanceId) {
                    OverlayOptionPane(
                        task = selected,
                        locked = locked,
                        onSetOption = { option, value ->
                            onIntent(
                                SessionIntent.SetTaskOption(
                                    active.id,
                                    selected.instanceId,
                                    option,
                                    value,
                                ),
                            )
                        },
                    )
                }
            }

            else -> OverlayEmptyHint(
                title = stringResource(R.string.overlay_pane_view_empty_title),
                lines = listOf(stringResource(R.string.overlay_pane_view_empty_hint)),
            )
        }
    }
}

@Composable
private fun OverlayOptionPane(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    onSetOption: (String, OptionValue) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Text(
            text = task.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        OverlayOptionEditorList(
            options = task.options,
            locked = locked,
            onSetOption = onSetOption,
        )
        task.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverlayAddPane(
    catalog: List<TaskCatalogGroup>,
    locked: Boolean,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember(catalog) { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable(catalog) {
        mutableStateOf(catalog.firstOrNull()?.groupName.orEmpty())
    }
    val filtered = remember(catalog, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            catalog
        } else {
            catalog.mapNotNull { group ->
                val tasks = group.tasks.filter {
                    it.label.contains(q, ignoreCase = true) ||
                        it.taskName.contains(q, ignoreCase = true)
                }
                if (tasks.isEmpty()) null else group.copy(tasks = tasks)
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        OverlayField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(R.string.tasks_search_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
        val rows = if (query.isNotBlank()) {
            filtered.flatMap { it.tasks }
        } else {
            (catalog.firstOrNull { it.groupName == groupName } ?: catalog.firstOrNull())
                ?.tasks
                .orEmpty()
        }
        if (query.isBlank() && catalog.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                items(catalog, key = { it.groupName }) { group ->
                    val on = group.groupName == groupName
                    Text(
                        text = group.asUiText().asString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.maaClickable(onClick = { groupName = group.groupName }),
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
        ) {
            items(rows, key = { it.taskName }) { item ->
                OverlayTile(
                    selected = item.taskName in selected,
                    enabled = !locked,
                    onClick = { selected.setPresent(item.taskName, item.taskName !in selected) },
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        OverlayBarButton(
            onClick = { onConfirm(selected.toList()) },
            enabled = !locked && selected.isNotEmpty(),
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (selected.isEmpty()) {
                    stringResource(R.string.tasks_confirm_add)
                } else {
                    stringResource(R.string.tasks_confirm_add_count, selected.size)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun OverlayTaskManagePane(
    active: ResolvedRunConfiguration,
    task: ResolvedConfiguredTask,
    locked: Boolean,
    onIntent: (SessionIntent) -> Unit,
) {
    var name by remember(task.instanceId) { mutableStateOf(task.label) }
    var confirmingDelete by remember(task.instanceId) { mutableStateOf(false) }
    val copySuffix = stringResource(R.string.tasks_copy_suffix)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Text(text = task.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        OverlayField(
            value = name,
            onValueChange = { name = it },
            enabled = !locked,
            hint = stringResource(R.string.tasks_rename_label),
            modifier = Modifier.fillMaxWidth(),
            onDone = {
                if (!locked && name.isNotBlank() && name.trim() != task.label) {
                    onIntent(SessionIntent.RenameTask(active.id, task.instanceId, name.trim()))
                }
            },
        )
        OverlayBarButton(
            onClick = {
                onIntent(SessionIntent.RenameTask(active.id, task.instanceId, name.trim()))
            },
            enabled = !locked && name.isNotBlank() && name.trim() != task.label,
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_save), style = MaterialTheme.typography.labelSmall)
        }
        OverlayBarButton(
            onClick = {
                val copyName = uniqueCopyLabel(task.label, copySuffix, active.tasks.map { it.label })
                onIntent(SessionIntent.DuplicateTask(active.id, task.instanceId, copyName))
            },
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.tasks_duplicate), style = MaterialTheme.typography.labelSmall)
        }
        if (confirmingDelete) {
            Text(
                text = stringResource(R.string.overlay_delete_task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Overlay.gap)) {
                OverlayBarButton(
                    onClick = { confirmingDelete = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dialog_cancel), style = MaterialTheme.typography.labelSmall)
                }
                OverlayBarButton(
                    onClick = {
                        onIntent(SessionIntent.RemoveTask(active.id, task.instanceId))
                        confirmingDelete = false
                    },
                    enabled = !locked,
                    filled = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.tasks_remove), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            OverlayBarButton(
                onClick = { confirmingDelete = true },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.tasks_remove), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OverlayConfigPane(
    state: SessionUiState,
    locked: Boolean,
    onIntent: (SessionIntent) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<RunConfigurationId?>(null) }
    var deletingId by remember { mutableStateOf<RunConfigurationId?>(null) }
    val renaming = renamingId?.let { id -> state.configurationList.firstOrNull { it.id == id } }
    val deleting = deletingId?.let { id -> state.configurationList.firstOrNull { it.id == id } }

    when {
        creating -> OverlayNameForm(
            title = stringResource(R.string.config_create_empty),
            initial = uniqueOverlayName(
                stringResource(R.string.config_empty_default_name),
                state.configurationList.map { it.name },
            ),
            enabled = !locked,
            onCancel = { creating = false },
            onSubmit = { name ->
                onIntent(SessionIntent.CreateConfiguration(name))
                creating = false
            },
        )

        renaming != null -> OverlayNameForm(
            title = stringResource(R.string.config_rename_title),
            initial = renaming.name,
            enabled = !locked,
            onCancel = { renamingId = null },
            onSubmit = { name ->
                onIntent(SessionIntent.RenameConfiguration(renaming.id, name))
                renamingId = null
            },
        )

        deleting != null -> Column(
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.overlay_delete_config),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(text = deleting.name, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Overlay.gap)) {
                OverlayBarButton(onClick = { deletingId = null }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dialog_cancel), style = MaterialTheme.typography.labelSmall)
                }
                OverlayBarButton(
                    onClick = {
                        onIntent(SessionIntent.DeleteConfiguration(deleting.id))
                        deletingId = null
                    },
                    enabled = !locked,
                    filled = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_delete), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.config_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                OverlayBarButton(
                    onClick = { creating = true },
                    enabled = !locked,
                    modifier = Modifier.widthIn(min = MaaDesignTokens.Overlay.bar),
                ) {
                    Text(stringResource(R.string.overlay_new_config), style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                items(state.configurationList, key = { it.id.value }) { configuration ->
                    val existingNames = state.configurationList.map { it.name }
                    val duplicateName = uniqueOverlayName(
                        stringResource(R.string.config_copy_name, configuration.name),
                        existingNames,
                    )
                    OverlayTile(
                        selected = configuration.isActive,
                        enabled = !locked,
                        onClick = { onIntent(SessionIntent.SelectConfiguration(configuration.id)) },
                    ) {
                        Text(
                            text = configuration.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        OverlayIconHit(
                            icon = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.common_copy),
                            onClick = {
                                onIntent(SessionIntent.DuplicateConfiguration(configuration.id, duplicateName))
                            },
                            enabled = !locked,
                        )
                        OverlayIconHit(
                            icon = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.common_rename),
                            onClick = { renamingId = configuration.id },
                            enabled = !locked,
                        )
                        OverlayIconHit(
                            icon = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.common_delete),
                            onClick = { deletingId = configuration.id },
                            enabled = !locked,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayNameForm(
    title: String,
    initial: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        OverlayField(
            value = name,
            onValueChange = { name = it },
            enabled = enabled,
            hint = stringResource(R.string.config_name_label),
            modifier = Modifier.fillMaxWidth(),
            onDone = { if (enabled && name.isNotBlank()) onSubmit(name.trim()) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Overlay.gap)) {
            OverlayBarButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dialog_cancel), style = MaterialTheme.typography.labelSmall)
            }
            OverlayBarButton(
                onClick = { onSubmit(name.trim()) },
                enabled = enabled && name.isNotBlank(),
                filled = true,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_save), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OverlayEmptyHint(title: String, lines: List<String>) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun uniqueOverlayName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var index = 2
    while ("$base $index" in existing) index++
    return "$base $index"
}
