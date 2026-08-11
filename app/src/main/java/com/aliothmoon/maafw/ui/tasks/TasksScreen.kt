package com.aliothmoon.maafw.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaEmptyState

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
                    MaaOutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) {
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
    var showRunLog by rememberSaveable { mutableStateOf(false) }
    var showQuickOptions by rememberSaveable { mutableStateOf(false) }

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
                        watchdogState = state.watchdogState,
                        content = previewContent,
                        onEnterFullscreen = onEnterFullscreen,
                    )
                    ConfigurationSelectorRow(
                        active = active,
                        locked = locked,
                        logOpen = showRunLog,
                        onSelectConfig = { showConfigSheet = true },
                        onAddTasks = { showAddTasks = true },
                        onToggleLog = { showRunLog = !showRunLog },
                    )

                    // 日志接管任务列表那块，不另开弹层：这两样不会同时看，
                    // 而全屏弹层会把下面那颗启停按钮一起挡掉
                    //
                    // 走竖向而不是横向：横向是 pager 换 tab 的语汇，同一块地方两种含义的
                    // 横滑会读混。日志从上方压下来，正对着上面那颗开关
                    AnimatedContent(
                        targetState = showRunLog,
                        transitionSpec = {
                            val opening = targetState
                            // 先淡出、后淡入（Material 的 fade through）：两边都没有不透明底，
                            // 齐头并进时任务卡片会一片片盖住下面的日志文字，盖住的当场消失、
                            // 缝隙里的还在，看着就是闪
                            //
                            // 但也不能完全错开——交接那一帧两边都是全透明，空一帧同样像闪
                            // 让淡入早于淡出结束一点点，重叠区间落在两边都很淡的地方
                            val enter = fadeIn(
                                tween(FadeInMillis, FadeInDelayMillis, MaaMotion.EmphasizedDecelerate),
                            ) + slideInVertically(
                                tween(FadeInMillis, FadeInDelayMillis, MaaMotion.EmphasizedDecelerate),
                            ) { if (opening) -it / SlideFraction else it / SlideFraction }
                            // 退场只淡不滑：这一段本来就短，位移看不出来，
                            // 却要多维护一份跟进场对齐的方向
                            val exit = fadeOut(
                                tween(FadeOutMillis, easing = MaaMotion.EmphasizedAccelerate),
                            )
                            enter.togetherWith(exit)
                        },
                        label = "tasksBody",
                        modifier = Modifier.weight(1f),
                    ) { logOpen ->
                        when {
                            logOpen -> RunLogPanel(
                                entries = runLog,
                                onClear = { onIntent(SessionIntent.ClearRunLog) },
                                modifier = Modifier.fillMaxSize(),
                            )

                            active == null -> MaaEmptyState(
                                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                title = stringResource(R.string.tasks_empty_config_title),
                                hint = stringResource(R.string.tasks_empty_config_hint),
                                modifier = Modifier.fillMaxSize(),
                            )

                            else -> TaskList(
                                active = active,
                                locked = locked,
                                onIntent = onIntent,
                                onRequestAddTasks = { showAddTasks = true },
                                onEditTask = { editingTaskInstanceId = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(MaaDesignTokens.Spacing.xs))
                RunnerToggleButton(
                    state = state,
                    quickOptionsOpen = showQuickOptions,
                    onIntent = onIntent,
                    onToggleQuickOptions = { showQuickOptions = !showQuickOptions },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (showQuickOptions) {
            BackHandler { showQuickOptions = false }
            TasksQuickOptionsPanel(
                state = state,
                onIntent = onIntent,
                onDismiss = { showQuickOptions = false },
            )
        }
    }

    // 日志开着时返回键先收日志，不要直接退出这一屏
    if (showRunLog) {
        BackHandler { showRunLog = false }
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

/**
 * 切换时的位移取区域高度的几分之一
 *
 * 这块地方有小半屏高，取 1/3 那类比例位移一百多 dp，一个开关不该有这么大的动静
 */
private const val SlideFraction = 6

// 淡出 0–120ms，淡入 80–300ms：只在 80–120 这 40ms 重叠，那会儿两边都不到两成不透明度，
// 卡片底色淡得盖不住东西。整体仍是 MaaMotion.DURATION_MEDIUM
private const val FadeOutMillis = 120
private const val FadeInDelayMillis = 80
private const val FadeInMillis = MaaMotion.DURATION_MEDIUM - FadeInDelayMillis
