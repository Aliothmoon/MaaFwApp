package com.aliothmoon.maafw.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton

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
    /** 取值而不是值：日志面板没开时这一层不该跟着日志频率重组 */
    runLog: () -> List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onExportLogs: () -> Unit,
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
                onExportLogs = onExportLogs,
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
    runLog: () -> List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    TaskWorkspace(
                        state = state,
                        runLog = runLog,
                        onExportLogs = onExportLogs,
                        onIntent = onIntent,
                        showLogToggle = true,
                        modifier = Modifier.weight(1f),
                    )
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
}
