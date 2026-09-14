package com.aliothmoon.maafw.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import android.graphics.Rect
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.pip.LocalIsInPip
import com.aliothmoon.maafw.ui.pip.PipController
import com.aliothmoon.maafw.ui.pip.PipHost
import com.aliothmoon.maafw.ui.pip.PipRequest

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
    /** 用户停留的页是否是本页；画中画只在该页武装（pager 会预组合相邻页，组合≠可见） */
    isActivePage: Boolean,
    pipOnHome: Boolean,
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
                isActivePage = isActivePage,
                pipOnHome = pipOnHome,
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
    isActivePage: Boolean,
    pipOnHome: Boolean,
    runLog: () -> List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQuickOptions by rememberSaveable { mutableStateOf(false) }

    // 小窗里只让出画面，本页其余部分照常组合——拆掉的话展开时要整页重组，
    // 列表滚动与面板开合也跟着丢
    val pipActive = LocalIsInPip.current

    // 全屏态不用显式排除：预览搬去全屏宿主后 previewContent == null，天然不在武装范围
    val context = LocalContext.current
    val pipHost = context as? PipHost
    val previewResolution = state.previewResolution
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    val pipEligible = pipOnHome &&
            isActivePage &&
            previewContent != null &&
            previewResolution != null &&
            state.runMode == RunMode.BACKGROUND &&
            state.runner.phase.isBusy &&
            previewSurfaceReady &&
            pipHost != null &&
            PipController.isSupported(context)
    DisposableEffect(pipHost, pipEligible, previewResolution, previewBounds) {
        fun arm(enabled: Boolean, sourceRect: Rect?) {
            val host = pipHost ?: return
            val activity = host as? android.app.Activity ?: return
            val resolution = previewResolution ?: return
            val request = PipRequest(resolution, sourceRect)
            host.pipRequest = if (enabled) request else null
            PipController.updateParams(activity, enabled, request)
        }
        arm(pipEligible, previewBounds)
        onDispose { arm(enabled = false, sourceRect = null) }
    }

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
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = MaaDesignTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    LivePreview(
                        resolution = state.previewResolution,
                        surfaceReady = previewSurfaceReady,
                        running = state.runner.phase.isBusy,
                        watchdogState = state.watchdogState,
                        content = previewContent.takeUnless { pipActive },
                        onEnterFullscreen = onEnterFullscreen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(3f),
                        // 小窗里这张卡缩成了巴掌大，别拿它的坐标覆盖 sourceRectHint
                        onBoundsChanged = { if (!pipActive) previewBounds = it },
                    )
                    TaskWorkspace(
                        state = state,
                        runLog = runLog,
                        onExportLogs = onExportLogs,
                        onIntent = onIntent,
                        showLogToggle = true,
                        modifier = Modifier.weight(7f),
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
