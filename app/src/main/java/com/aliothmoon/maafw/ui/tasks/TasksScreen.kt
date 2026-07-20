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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    // 前置态切换（Loading/Error/Ready）整体淡入淡出
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

    // 配置选择 sheet；新建/模板预览/重命名全部内嵌在 sheet 内部
    var showConfigSheet by rememberSaveable { mutableStateOf(false) }

    // 服务器（资源）切换 sheet：入口在配置卡右侧 chip，项目无候选时不展示
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    val environment = state.environment
    val serverCandidates = environment?.resourceCandidates.orEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // 外层 AppRoot 的 Scaffold 已应用状态栏/导航栏 inset，内层不再重复消费
        contentWindowInsets = WindowInsets(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MaaDesignTokens.Spacing.lg),
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
                // 拖拽期间用本地顺序渲染，松手后发 MoveTask，等 DataStore 回流后回到权威顺序
                val canonicalIds = remember(active.tasks) { active.tasks.map { it.instanceId } }
                val canonicalState = rememberUpdatedState(canonicalIds)
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
                        )
                        Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
                        // 计数弱化为标题后缀，整行只保留标题块与添加钮两个视觉元素
                        Text(
                            text = stringResource(
                                R.string.tasks_effective_count,
                                active.effectiveTaskCount,
                                active.tasks.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // 轻量文字添加钮：主色文字无底色，不与白底任务卡抢层级
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
                        contentPadding = PaddingValues(bottom = MaaDesignTokens.Spacing.lg),
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
                                    // 增删/换位时的位置与淡入动画（reorderable 兼容）
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
            RunnerToggleButton(
                state = state,
                onIntent = onIntent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaaDesignTokens.Spacing.xs),
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
            onSelect = { id ->
                showConfigSheet = false
                onIntent(SessionIntent.SelectConfiguration(id))
            },
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

/**
 * 底部运行区 split button：左段为原启停开关——Idle 显示「开始」，
 * 运行/准备中切换为「停止」，停止中降级为不可点的「停止中…」；
 * 右段为运行选项弹出（仅 UI 展示），与运行不冲突，不随运行锁定。
 */
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
    // 外缘使用紧凑 8dp 圆角，分割处保留更小圆角，2dp 分缝形成 split 视觉；
    // 外层 Surface 只负责统一底色和轮廓，不加投影，避免底部栏向导航区落阴影。
    val outer = MaaDesignTokens.CornerRadius.inner
    val inner = 4.dp
    BoxWithConstraints(modifier = modifier) {
        val menuWidth = maxWidth
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(outer),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // M3 按钮自带隐形 48dp 最小触控留白，白底 Surface 会把它显形成白边；
            // 容器内取消该强制值，让 Surface 紧贴按钮实际高度
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
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

/**
 * 启动键右段：打开由整条运行栏锚定的运行选项菜单。
 */
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

/**
 * 实时截图（Live Preview）占位区域：固定 16:9。
 * 不进入 RunnerState/RunnerEvent，后续单独实现（docs/android-ui-contract.md §10）。
 */
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

/** 服务器（资源）选择行：独立于配置卡，整行点击弹服务器 sheet；无候选时调用方不渲染。 */
@Composable
private fun ServerSelectorRow(
    /** 当前服务器（资源）label；未选择时由调用方传入占位文案。 */
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
            // 去掉图标/标题后行内只剩文字，垂直 padding 加大保证触控高度
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

/** 当前配置卡片：点击弹出锚定切换菜单。 */
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
            // 配置标识图标块：卡片的视觉锚点
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
            // 圆形 tonal 按钮：明确可点 affordance
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

/** 配置 sheet 内部页面：主页 / 模板预览 / 新建空配置，全程不离开 sheet。 */
private sealed interface ConfigSheetPage {
    /** 导航层级：决定页面切换的滑动方向（前进右进、返回左进）。 */
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

/** 底部上弹抽屉：配置卡片 + 模板区；模板预览与新建空配置内嵌为子页，免去二次弹窗。 */
@Composable
private fun ConfigurationSheet(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    onSelect: (RunConfigurationId) -> Unit,
    onRename: (RunConfigurationId, String) -> Unit,
    onDelete: (RunConfigurationId) -> Unit,
    onCreateEmpty: (name: String) -> Unit,
    onCreateFromTemplate: (templateName: String, configurationName: String, taskNames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<ConfigSheetPage>(ConfigSheetPage.Home) }
    // 主页双页签：0 = 选择配置，1 = 从模板新建；从模板预览返回时保持在模板页
    var homeTab by rememberSaveable { mutableStateOf(0) }
    val existingNames = state.configurationList.map { it.name }

    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val enter = fadeIn(MaaMotion.enter<Float>()) + slideInHorizontally(MaaMotion.enter()) {
                    if (forward) it / 3 else -it / 3
                }
                val exit = fadeOut(MaaMotion.exit<Float>()) + slideOutHorizontally(MaaMotion.exit()) {
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
                    tab = homeTab,
                    onTabChange = { homeTab = it },
                    onSelect = onSelect,
                    onOpenRename = { page = ConfigSheetPage.Rename(it) },
                    onDelete = onDelete,
                    onOpenCreateEmpty = { page = ConfigSheetPage.CreateEmpty },
                    onOpenTemplate = { page = ConfigSheetPage.TemplatePreview(it) },
                    onClose = onDismiss,
                )

                is ConfigSheetPage.Rename -> {
                    val configuration = state.configurationList.firstOrNull { it.id == current.id }
                    if (configuration == null) {
                        // 目标配置已消失（并发删除/重载）时退回主页
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        RenameConfigurationPage(
                            configuration = configuration,
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
                    onBack = { page = ConfigSheetPage.Home },
                    onClose = onDismiss,
                    onCreate = onCreateEmpty,
                )

                is ConfigSheetPage.TemplatePreview -> {
                    val template = templates.firstOrNull { it.name == current.templateName }
                    if (template == null) {
                        // 模板已消失（项目重载）时退回主页
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        TemplatePreviewPage(
                            template = template,
                            existingNames = existingNames,
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

/** 配置 sheet 主页：「选择配置 / 从模板新建」双页签，分别承载配置列表与模板列表。 */
@Composable
private fun ConfigSheetHomePage(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onSelect: (RunConfigurationId) -> Unit,
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
                        ConfigRowCard(
                            configuration = configuration,
                            onSelect = { onSelect(configuration.id) },
                            onRename = { onOpenRename(configuration.id) },
                            onDelete = { onDelete(configuration.id) },
                        )
                    }
                    item(key = "create-empty") {
                        CreateEmptyCard(onClick = onOpenCreateEmpty)
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
                            TemplateCard(template = template, onClick = { onOpenTemplate(template.name) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(MaaDesignTokens.Spacing.sm))
    }
}

/** 模板预览页：命名 + 挑选任务 + 创建并使用，一步完成。 */
@Composable
private fun TemplatePreviewPage(
    template: ConfigurationTemplate,
    existingNames: List<String>,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCreate: (name: String, taskNames: List<String>) -> Unit,
) {
    val templateTasks = remember(template) { template.distinctTasks }
    var name by rememberSaveable(template.name) {
        mutableStateOf(uniqueConfigurationName(template.label, existingNames))
    }
    // 带入任务集合：默认全选，创建时由模板声明顺序决定最终顺序
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
                    onToggle = { checked -> included.setPresent(task.taskName, checked) },
                )
            }
        }
        Button(
            onClick = { onCreate(name.trim(), included.toList()) },
            enabled = name.isNotBlank() && included.isNotEmpty(),
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

/** 模板任务行：整行可点切换是否带入；模板内停用的任务保留提示。 */
@Composable
private fun TemplateTaskRow(
    task: TemplateTask,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = { onToggle(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
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

/** 新建空配置页：命名后直接创建并使用。 */
@Composable
private fun CreateEmptyPage(
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
            enabled = name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.config_create_use))
        }
    }
}

/** 重命名配置页：预填当前名并全选，直接输入即整体替换；与新建/模板预览同为 sheet 子页。 */
@Composable
private fun RenameConfigurationPage(
    configuration: ResolvedRunConfiguration,
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
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val commit = { if (name.text.isNotBlank()) onRename(name.text.trim()) }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_rename_title), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = commit,
            enabled = name.text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}

/** 生成不与现有配置重名的预填名称：base、base 2、base 3…（仅预填，不强制唯一）。 */
private fun uniqueConfigurationName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var index = 2
    while ("$base $index" in existing) index++
    return "$base $index"
}

/** 新建空配置入口卡：描边占位样式，与配置卡片同尺寸。 */
@Composable
private fun CreateEmptyCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick),
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

/** 配置卡片：active 主色描边 + 容器底色 + 单选圈；行尾重命名（滑入子页）/删除。 */
@Composable
private fun ConfigRowCard(
    configuration: ResolvedRunConfiguration,
    onSelect: () -> Unit,
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
            .maaClickable(onClick = onSelect),
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
            // 单选圈：active 实心主色 + 对勾
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
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.common_rename),
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
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
private fun TemplateCard(template: ConfigurationTemplate, onClick: () -> Unit) {
    MaaCard(
        modifier = Modifier.maaClickable(onClick = onClick),
        // 行卡密度：与同 sheet 的配置行卡同档（单行 ~48dp）
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
                // 复数档位由总数决定
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
    // 未勾选的任务内容整体降透明度，只保留 Checkbox 与操作按钮的正常态
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
        // 行卡密度：Checkbox 与操作钮自带 48dp 触控留白，贴边侧只留 4dp
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
            // 可点性提示：仅有 option 的任务行可打开设置 sheet，无 option 的行不渲染箭头
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
