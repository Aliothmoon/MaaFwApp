package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TemplateTask
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.i18n.localized

@Composable
fun TasksScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = state.projectState,
        animationSpec = MaaMotion.enter(),
        label = "projectState",
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
                        modifier = Modifier.size(40.dp),
                    )
                    Text(stringResource(R.string.tasks_project_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) {
                        Text(stringResource(R.string.tasks_reload))
                    }
                }
            }

            is ProjectState.Ready -> TasksContent(state, onIntent)
        }
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
    var editingTaskInstanceId by rememberSaveable { mutableStateOf<String?>(null) }

    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    val environment = state.environment
    val serverCandidates = environment?.resourceCandidates.orEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                LivePreviewPlaceholder()
                if (serverCandidates.isNotEmpty()) {
                    ServerSelectorRow(
                        label = environment?.resource?.label ?: stringResource(R.string.settings_not_selected),
                        locked = locked,
                        onClick = { showServerSheet = true },
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

                    if (active.tasks.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.AddTask,
                            title = stringResource(R.string.tasks_empty_task_title),
                            hint = stringResource(R.string.tasks_empty_task_hint),
                            modifier = Modifier.weight(1f),
                            action = {
                                FilledTonalButton(
                                    onClick = { showAddTasks = true },
                                    enabled = !locked,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
                                    Text(stringResource(R.string.tasks_add_task))
                                }
                            },
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.tasks_run_order),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            val addTint = if (locked) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.button))
                                    .maaClickable(enabled = !locked, onClick = { showAddTasks = true })
                                    .padding(
                                        horizontal = MaaDesignTokens.Spacing.xs,
                                        vertical = MaaDesignTokens.Spacing.xxs,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = addTint,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(MaaDesignTokens.Spacing.xxs))
                                Text(
                                    text = stringResource(R.string.tasks_add_task),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = addTint,
                                )
                            }
                        }
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
                                            if (task.hasOptions) editingTaskInstanceId = task.instanceId
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(MaaDesignTokens.Spacing.xs))
            RunnerToggleButton(
                state = state,
                onIntent = onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showServerSheet && serverCandidates.isNotEmpty()) {
        ServerSwitchSheet(
            candidates = serverCandidates,
            currentName = environment?.resource?.name,
            onSelect = { onIntent(SessionIntent.SelectResource(it)) },
            onDismiss = { showServerSheet = false },
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

/** 左段启停；右段运行选项仅 UI，不随 configurationLocked */
@Composable
private fun RunnerToggleButton(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.runner.phase
    var extraOptionsExpanded by remember { mutableStateOf(false) }
    var muteGame by rememberSaveable { mutableStateOf(false) }
    var screensaverAutoplay by rememberSaveable { mutableStateOf(false) }
    val outer = MaaDesignTokens.CornerRadius.inner
    val inner = 4.dp
    BoxWithConstraints(modifier = modifier) {
        val menuWidth = maxWidth
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(outer),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val toggleShape = RoundedCornerShape(
                    topStart = outer,
                    bottomStart = outer,
                    topEnd = inner,
                    bottomEnd = inner,
                )
                val toggleModifier = Modifier.weight(1f)
                if (phase.isBusy) {
                    OutlinedButton(
                        onClick = { onIntent(SessionIntent.Stop) },
                        enabled = phase != RunnerPhase.Stopping,
                        shape = toggleShape,
                        modifier = toggleModifier,
                    ) {
                        Text(
                            stringResource(
                                if (phase == RunnerPhase.Stopping) {
                                    R.string.runner_stopping
                                } else {
                                    R.string.runner_stop
                                },
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = { onIntent(SessionIntent.Start) },
                        enabled = state.canStart,
                        shape = toggleShape,
                        modifier = toggleModifier,
                    ) {
                        Text(stringResource(R.string.runner_start))
                    }
                }
                ExtraOptionsSegment(
                    shape = RoundedCornerShape(
                        topStart = inner,
                        bottomStart = inner,
                        topEnd = outer,
                        bottomEnd = outer,
                    ),
                    onClick = { extraOptionsExpanded = true },
                )
            }
        }
        DropdownMenu(
            expanded = extraOptionsExpanded,
            onDismissRequest = { extraOptionsExpanded = false },
            offset = DpOffset(x = 0.dp, y = -MaaDesignTokens.Spacing.sm),
            modifier = Modifier.width(menuWidth),
        ) {
            Text(
                text = stringResource(R.string.runner_extra_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = MaaDesignTokens.Spacing.lg,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
            )
            Column(
                modifier = Modifier.padding(
                    start = MaaDesignTokens.Spacing.sm,
                    end = MaaDesignTokens.Spacing.sm,
                    bottom = MaaDesignTokens.Spacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                ExtraOptionSwitchRow(
                    label = stringResource(R.string.extra_mute_game),
                    checked = muteGame,
                    onCheckedChange = { muteGame = it },
                )
                ExtraOptionSwitchRow(
                    label = stringResource(R.string.extra_screensaver_autoplay),
                    checked = screensaverAutoplay,
                    onCheckedChange = { screensaverAutoplay = it },
                )
            }
        }
    }
}

@Composable
private fun ExtraOptionsSegment(
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = shape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.width(64.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = stringResource(R.string.runner_extra_options),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ExtraOptionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.inner),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                horizontal = MaaDesignTokens.Spacing.lg,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            MaaSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** Live Preview 占位；不进 RunnerState/RunnerEvent（docs/android-ui-contract.md §10） */
@Composable
private fun LivePreviewPlaceholder() {
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaaDesignTokens.Spacing.md)
            .aspectRatio(16f / 9f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OndemandVideo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = stringResource(R.string.tasks_live_preview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun ServerSelectorRow(
    label: String,
    locked: Boolean,
    onClick: () -> Unit,
) {
    val valueTint = if (locked) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = !locked, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.md,
                vertical = MaaDesignTokens.Spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = valueTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = valueTint,
                modifier = Modifier.size(20.dp),
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
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = !locked, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaaDesignTokens.Spacing.md,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.button))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
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
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    imageVector = Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private sealed interface ConfigSheetPage {
    /** 导航深度；AnimatedContent 用 depth 差决定滑入方向 */
    val depth: Int

    data object Home : ConfigSheetPage {
        override val depth = 0
    }

    data class TemplatePreview(val templateName: String) : ConfigSheetPage {
        override val depth = 1
    }

    data object CreateEmpty : ConfigSheetPage {
        override val depth = 1
    }

    data class Rename(val id: RunConfigurationId) : ConfigSheetPage {
        override val depth = 1
    }
}

@Composable
private fun ConfigurationSheet(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    locked: Boolean,
    onSelect: (RunConfigurationId) -> Unit,
    onDuplicate: (RunConfigurationId, String) -> Unit,
    onRename: (RunConfigurationId, String) -> Unit,
    onDelete: (RunConfigurationId) -> Unit,
    onCreateEmpty: (name: String) -> Unit,
    onCreateFromTemplate: (templateName: String, configurationName: String, taskNames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<ConfigSheetPage>(ConfigSheetPage.Home) }
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    val existingNames = state.configurationList.map { it.name }

    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val enter = fadeIn(MaaMotion.enter()) + slideInHorizontally(MaaMotion.enter()) {
                    if (forward) it / 3 else -it / 3
                }
                val exit = fadeOut(MaaMotion.exit()) + slideOutHorizontally(MaaMotion.exit()) {
                    if (forward) -it / 3 else it / 3
                }
                enter.togetherWith(exit)
            },
            label = "configSheetPage",
            modifier = sheetModifier.imePadding(),
        ) { current ->
            when (current) {
                is ConfigSheetPage.Home -> ConfigSheetHomePage(
                    state = state,
                    templates = templates,
                    locked = locked,
                    tab = homeTab,
                    onTabChange = { homeTab = it },
                    onSelect = onSelect,
                    onDuplicate = onDuplicate,
                    onOpenRename = { page = ConfigSheetPage.Rename(it) },
                    onDelete = onDelete,
                    onOpenCreateEmpty = { page = ConfigSheetPage.CreateEmpty },
                    onOpenTemplate = { page = ConfigSheetPage.TemplatePreview(it) },
                    onClose = onDismiss,
                )

                is ConfigSheetPage.Rename -> {
                    val configuration = state.configurationList.firstOrNull { it.id == current.id }
                    if (configuration == null) {
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        RenameConfigurationPage(
                            configuration = configuration,
                            writeEnabled = !locked,
                            onBack = { page = ConfigSheetPage.Home },
                            onClose = onDismiss,
                            onRename = { name ->
                                page = ConfigSheetPage.Home
                                onRename(configuration.id, name)
                            },
                        )
                    }
                }

                is ConfigSheetPage.CreateEmpty -> CreateEmptyPage(
                    writeEnabled = !locked,
                    onBack = { page = ConfigSheetPage.Home },
                    onClose = onDismiss,
                    onCreate = onCreateEmpty,
                )

                is ConfigSheetPage.TemplatePreview -> {
                    val template = templates.firstOrNull { it.name == current.templateName }
                    if (template == null) {
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        TemplatePreviewPage(
                            template = template,
                            existingNames = existingNames,
                            writeEnabled = !locked,
                            onBack = { page = ConfigSheetPage.Home },
                            onClose = onDismiss,
                            onCreate = { name, taskNames ->
                                onCreateFromTemplate(template.name, name, taskNames)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSheetHomePage(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    locked: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onSelect: (RunConfigurationId) -> Unit,
    onDuplicate: (RunConfigurationId, String) -> Unit,
    onOpenRename: (RunConfigurationId) -> Unit,
    onDelete: (RunConfigurationId) -> Unit,
    onOpenCreateEmpty: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_title), onClose = onClose)
        val tabs = buildList {
            add(0 to stringResource(R.string.config_select))
            if (templates.isNotEmpty()) {
                add(1 to stringResource(R.string.config_create_from_template))
            }
        }
        MaaSingleChoiceFlow(
            options = tabs,
            selected = if (templates.isEmpty()) 0 else tab,
            onSelect = onTabChange,
        )
        val showTemplates = tab == 1 && templates.isNotEmpty()
        Crossfade(
            targetState = showTemplates,
            animationSpec = MaaMotion.enter(),
            label = "configSheetTab",
            modifier = Modifier.weight(1f),
        ) { templatesPage ->
            if (!templatesPage) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    items(state.configurationList, key = { it.id.value }) { configuration ->
                        val duplicatedName = uniqueConfigurationName(
                            base = stringResource(R.string.config_copy_name, configuration.name),
                            existing = state.configurationList.map { it.name },
                        )
                        ConfigRowCard(
                            configuration = configuration,
                            writeEnabled = !locked,
                            onSelect = { onSelect(configuration.id) },
                            onDuplicate = { onDuplicate(configuration.id, duplicatedName) },
                            onRename = { onOpenRename(configuration.id) },
                            onDelete = { onDelete(configuration.id) },
                        )
                    }
                    item(key = "create-empty") {
                        CreateEmptyCard(
                            writeEnabled = !locked,
                            onClick = onOpenCreateEmpty,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text(
                        text = stringResource(R.string.config_template_list_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                    ) {
                        items(templates, key = { "template-${it.name}" }) { template ->
                            TemplateCard(
                                template = template,
                                writeEnabled = !locked,
                                onClick = { onOpenTemplate(template.name) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(MaaDesignTokens.Spacing.sm))
    }
}

@Composable
private fun TemplatePreviewPage(
    template: ConfigurationTemplate,
    existingNames: List<String>,
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCreate: (name: String, taskNames: List<String>) -> Unit,
) {
    val templateTasks = remember(template) { template.distinctTasks }
    var name by rememberSaveable(template.name) {
        mutableStateOf(uniqueConfigurationName(template.label, existingNames))
    }
    // 默认全选；创建顺序由模板声明序决定，不按勾选顺序
    val included = remember(template) {
        mutableStateListOf<String>().apply { templateTasks.forEach { add(it.taskName) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_create_from_template), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        template.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.config_included_tasks), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.config_included_count, included.size, templateTasks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            items(templateTasks, key = { it.taskName }) { task ->
                TemplateTaskRow(
                    task = task,
                    checked = task.taskName in included,
                    writeEnabled = writeEnabled,
                    onToggle = { checked -> included.setPresent(task.taskName, checked) },
                )
            }
        }
        Button(
            onClick = { onCreate(name.trim(), included.toList()) },
            enabled = writeEnabled && name.isNotBlank() && included.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(
                pluralStringResource(
                    R.plurals.config_create_use_count,
                    included.size,
                    included.size,
                ),
            )
        }
    }
}

@Composable
private fun TemplateTaskRow(
    task: TemplateTask,
    checked: Boolean,
    writeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = writeEnabled, onClick = { onToggle(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle, enabled = writeEnabled)
        Text(
            text = task.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!task.enabled) {
            MaaToneBadge(text = stringResource(R.string.config_task_disabled_by_default), tone = MaaTheme.palette.neutral)
        }
    }
}

@Composable
private fun CreateEmptyPage(
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_create_empty), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.config_create_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onCreate(name.trim()) },
            enabled = writeEnabled && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.config_create_use))
        }
    }
}

@Composable
private fun RenameConfigurationPage(
    configuration: ResolvedRunConfiguration,
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(configuration.id) {
        mutableStateOf(
            TextFieldValue(configuration.name, selection = TextRange(0, configuration.name.length)),
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (writeEnabled) focusRequester.requestFocus() }
    val commit = { if (writeEnabled && name.text.isNotBlank()) onRename(name.text.trim()) }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_rename_title), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = commit,
            enabled = writeEnabled && name.text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}

/** 预填不重名建议名（仅预填，不强制唯一） */
private fun uniqueConfigurationName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var index = 2
    while ("$base $index" in existing) index++
    return "$base $index"
}

@Composable
private fun CreateEmptyCard(
    writeEnabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = writeEnabled, onClick = onClick)
            .alpha(if (writeEnabled) 1f else 0.5f),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
            Text(
                text = stringResource(R.string.config_create_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ConfigRowCard(
    configuration: ResolvedRunConfiguration,
    writeEnabled: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = configuration.isActive
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = writeEnabled, onClick = onSelect)
            .alpha(if (writeEnabled) 1f else 0.6f),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (active) 1.dp else MaaDesignTokens.Separator.thickness,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = MaaDesignTokens.Spacing.md,
                end = MaaDesignTokens.Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shape = CircleShape,
                    )
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
            ) {
                if (active) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(MaaDesignTokens.Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = configuration.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.config_task_count,
                        configuration.tasks.size,
                        configuration.tasks.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = onDuplicate, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.common_copy),
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRename, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.common_rename),
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: ConfigurationTemplate,
    writeEnabled: Boolean,
    onClick: () -> Unit,
) {
    MaaCard(
        modifier = Modifier
            .maaClickable(enabled = writeEnabled, onClick = onClick)
            .alpha(if (writeEnabled) 1f else 0.5f),
        contentPadding = PaddingValues(MaaDesignTokens.Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = template.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.template_task_count,
                    template.tasks.size,
                    template.tasks.count { it.enabled },
                    template.tasks.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        template.description?.takeIf { it.isNotBlank() }?.let {
            MaaMarkdown(text = it, maxLines = 3, linksClickable = false)
        }
    }
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
    modifier: Modifier = Modifier,
) {
    // 未勾选只淡化文案区；Checkbox/删除钮保持可辨
    val contentAlpha by animateFloatAsState(
        targetValue = if (task.enabled) 1f else 0.55f,
        animationSpec = MaaMotion.enter(),
        label = "taskContentAlpha",
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 0.dp,
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
                        .size(20.dp)
                        .alpha(contentAlpha),
                )
            }
            IconButton(onClick = onRemove, enabled = !locked) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.tasks_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = {}, modifier = dragHandleModifier, enabled = !locked) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = stringResource(R.string.tasks_drag_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
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
