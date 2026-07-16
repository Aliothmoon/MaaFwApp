package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard

/**
 * 任务页：配置选择 + 任务列表 + 添加任务/option 编辑（bottom sheet）。
 * 前置态（C9）：项目 Loading/Error 时不渲染配置 UI。
 */
@Composable
fun TasksScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.projectState) {
        is ProjectState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is ProjectState.Error -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                Text("项目加载失败，请查看首页诊断", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) { Text("重新加载") }
            }
        }

        is ProjectState.Ready -> TasksContent(state, onIntent, modifier)
    }
}

@Composable
private fun TasksContent(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.configurationLocked
    val active = state.activeConfiguration

    var showAddTasks by rememberSaveable { mutableStateOf(false) }
    var editingTaskName by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (active != null && !locked) {
                ExtendedFloatingActionButton(
                    onClick = { showAddTasks = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("添加任务") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            ConfigurationSelector(state, onIntent, locked)

            if (active == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "没有配置，点击上方选择器新建",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 拖拽期间用本地顺序渲染，松手后发 MoveTask，等 DataStore 回流后回到权威顺序
                val canonicalNames = active.tasks.map { it.taskName }
                val canonicalState = rememberUpdatedState(canonicalNames)
                var pendingOrder by remember(active.id) { mutableStateOf<List<String>?>(null) }

                fun currentOrder(): List<String> {
                    val canonical = canonicalState.value
                    val pending = pendingOrder
                    // 集合已变化（增删任务）时废弃本地顺序
                    return if (pending != null && pending.toSet() == canonical.toSet()) pending else canonical
                }

                val listState = rememberLazyListState()
                val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                    pendingOrder = currentOrder().toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                }
                val tasksByName = active.tasks.associateBy { it.taskName }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    items(currentOrder(), key = { it }) { taskName ->
                        val task = tasksByName[taskName] ?: return@items
                        ReorderableItem(reorderableState, key = taskName) { isDragging ->
                            TaskRow(
                                task = task,
                                locked = locked,
                                isDragging = isDragging,
                                dragHandleModifier = Modifier.draggableHandle(
                                    enabled = !locked,
                                    onDragStopped = {
                                        val target = currentOrder().indexOf(taskName)
                                        val origin = canonicalState.value.indexOf(taskName)
                                        if (target >= 0 && origin >= 0 && target != origin) {
                                            onIntent(SessionIntent.MoveTask(active.id, taskName, target))
                                        }
                                    },
                                ),
                                onToggle = { enabled ->
                                    onIntent(SessionIntent.ToggleTask(active.id, task.taskName, enabled))
                                },
                                onRemove = {
                                    onIntent(SessionIntent.RemoveTask(active.id, task.taskName))
                                },
                                onClick = {
                                    if (task.hasOptions) editingTaskName = task.taskName
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddTasks && active != null) {
        AddTasksSheet(
            catalog = state.taskCatalog,
            onConfirm = { orderedNames ->
                onIntent(SessionIntent.ConfirmAddTasks(active.id, orderedNames))
                showAddTasks = false
            },
            onDismiss = { showAddTasks = false },
        )
    }

    val editingTask = editingTaskName?.let { name -> active?.tasks?.firstOrNull { it.taskName == name } }
    if (editingTask != null && active != null) {
        TaskOptionSheet(
            task = editingTask,
            locked = locked,
            onSetOption = { optionName, value ->
                onIntent(SessionIntent.SetTaskOption(active.id, editingTask.taskName, optionName, value))
            },
            onDismiss = { editingTaskName = null },
        )
    }
}

@Composable
private fun ConfigurationSelector(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    locked: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var overflowFor by remember { mutableStateOf<RunConfigurationId?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showTemplatePicker by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RunConfigurationId?>(null) }

    val active = state.activeConfiguration

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaaDesignTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = active?.name ?: "选择配置",
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.configurationList.forEach { configuration ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${configuration.name}（${configuration.tasks.size}）" +
                                    if (configuration.isActive) " ✓" else "",
                            )
                        },
                        onClick = {
                            expanded = false
                            onIntent(SessionIntent.SelectConfiguration(configuration.id))
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                expanded = false
                                overflowFor = configuration.id
                            }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("新建空配置") },
                    onClick = {
                        expanded = false
                        showCreateDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("从模板新建") },
                    onClick = {
                        expanded = false
                        showTemplatePicker = true
                    },
                )
            }
        }
    }

    // 单个配置的重命名/删除
    overflowFor?.let { targetId ->
        val target = state.configurationList.firstOrNull { it.id == targetId }
        AlertDialog(
            onDismissRequest = { overflowFor = null },
            title = { Text(target?.name ?: "配置") },
            text = { Text("对配置执行操作") },
            confirmButton = {
                TextButton(onClick = {
                    overflowFor = null
                    renameTarget = targetId
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    overflowFor = null
                    onIntent(SessionIntent.DeleteConfiguration(targetId))
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    if (showCreateDialog) {
        NameInputDialog(
            title = "新建空配置",
            initial = "",
            onConfirm = { name ->
                showCreateDialog = false
                onIntent(SessionIntent.CreateConfiguration(name))
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { targetId ->
        val current = state.configurationList.firstOrNull { it.id == targetId }
        NameInputDialog(
            title = "重命名配置",
            initial = current?.name.orEmpty(),
            onConfirm = { name ->
                renameTarget = null
                onIntent(SessionIntent.RenameConfiguration(targetId, name))
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (showTemplatePicker) {
        val templates = (state.projectState as? ProjectState.Ready)?.definition?.templates.orEmpty()
        AlertDialog(
            onDismissRequest = { showTemplatePicker = false },
            title = { Text("从模板新建") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
                    if (templates.isEmpty()) {
                        Text("项目没有提供预设模板")
                    }
                    templates.forEach { template ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTemplatePicker = false
                                    onIntent(SessionIntent.CreateFromTemplate(template.name))
                                }
                                .padding(vertical = MaaDesignTokens.Spacing.sm),
                        ) {
                            Text(template.name, style = MaterialTheme.typography.bodyLarge)
                            template.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplatePicker = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("配置名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TaskRow(
    task: ResolvedConfiguredTask,
    locked: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    MaaCard(
        modifier = Modifier
            .shadow(
                elevation = if (isDragging) 6.dp else 0.dp,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(enabled = task.hasOptions, onClick = onClick),
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
            Column(Modifier.weight(1f)) {
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
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (task.hasOptions) {
                    Text(
                        text = "点击编辑选项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove, enabled = !locked) {
                Icon(Icons.Outlined.Delete, contentDescription = "移除")
            }
            IconButton(onClick = {}, modifier = dragHandleModifier, enabled = !locked) {
                Icon(Icons.Outlined.DragIndicator, contentDescription = "拖动排序")
            }
        }
    }
}
