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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable

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
                    Text("项目加载失败，请查看首页诊断", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) { Text("重新加载") }
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
            ConfigurationSelectorCard(
                active = active,
                locked = locked,
                onClick = { showConfigSheet = true },
            )

            if (active == null) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    title = "还没有配置",
                    hint = "点击上方选择器新建空配置，或从模板快速创建",
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
                        title = "还没有任务",
                        hint = "从任务目录选择要执行的任务",
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
                                Text("添加任务")
                            }
                        },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "执行顺序",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
                        // 计数弱化为标题后缀，整行只保留标题块与添加钮两个视觉元素
                        Text(
                            text = "· ${active.effectiveTaskCount}/${active.tasks.size} 有效",
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
                                text = "添加任务",
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
 * 底部运行开关（替代首页的启停按钮）：Idle 显示「开始」，
 * 运行/准备中切换为「停止」，停止中降级为不可点的「停止中…」。
 */
@Composable
private fun RunnerToggleButton(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.runner.phase
    if (phase.isBusy) {
        OutlinedButton(
            onClick = { onIntent(SessionIntent.Stop) },
            enabled = phase != RunnerPhase.Stopping,
            modifier = modifier,
        ) {
            Text(if (phase == RunnerPhase.Stopping) "停止中…" else "停止")
        }
    } else {
        Button(
            onClick = { onIntent(SessionIntent.Start) },
            enabled = state.canStart,
            modifier = modifier,
        ) {
            Text("开始")
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
                    text = "实时预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
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
                    text = active?.name ?: "选择配置",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (active != null) {
                        "当前配置 · ${active.tasks.size} 个任务 · ${active.effectiveTaskCount} 个有效"
                    } else {
                        "点击选择或新建配置"
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            SheetTab(
                text = "选择配置",
                selected = tab == 0 || templates.isEmpty(),
                onClick = { onTabChange(0) },
            )
            if (templates.isNotEmpty()) {
                SheetTab(
                    text = "从模板新建",
                    selected = tab == 1,
                    onClick = { onTabChange(1) },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }
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
                        text = "预览模板内容，按需挑选任务后创建",
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

/** sheet 标题页签：选中态加粗主色调文本，未选中弱化。 */
@Composable
private fun SheetTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier
            .maaClickable(onClick = onClick)
            .padding(vertical = MaaDesignTokens.Spacing.xxs),
    )
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
        mutableStateOf(uniqueConfigurationName(template.name, existingNames))
    }
    // 带入任务集合：默认全选，创建时由模板声明顺序决定最终顺序
    val included = remember(template) {
        mutableStateListOf<String>().apply { templateTasks.forEach { add(it.taskName) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = "从模板新建", onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配置名称") },
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
            Text("带入任务", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${included.size}/${templateTasks.size} 个",
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
            Text("创建并使用（${included.size} 个任务）")
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
            text = task.taskName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!task.enabled) {
            MaaToneBadge(text = "默认停用", tone = MaaTheme.palette.neutral)
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
        MaaSheetHeader(title = "新建空配置", onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配置名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "创建后可通过「添加任务」从任务目录挑选任务",
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
            Text("创建并使用")
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
        MaaSheetHeader(title = "重命名配置", onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配置名称") },
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
            Text("保存")
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
                text = "新建空配置",
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
                    text = "${configuration.tasks.size} 个任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "重命名",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
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
                text = template.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${template.tasks.count { it.enabled }}/${template.tasks.size} 个任务",
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
                        text = it,
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
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = {}, modifier = dragHandleModifier, enabled = !locked) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = "拖动排序",
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
