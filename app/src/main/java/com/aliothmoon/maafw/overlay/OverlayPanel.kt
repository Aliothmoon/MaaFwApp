package com.aliothmoon.maafw.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.session.TaskSurface
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.tasks.RunLogPanel
import kotlinx.coroutines.launch

/**
 * 悬浮操作面板：任务 + 日志
 *
 * 控件走 overlay 自己的密度，不套任务页 MaaButton / 内容卡
 */
@Composable
fun OverlayPanel(
    state: SessionUiState,
    logEntries: () -> List<RunLogEntry>,
    isLocked: Boolean,
    onIntent: (SessionIntent) -> Unit,
    onBackToApp: () -> Unit,
    onExportLog: () -> Unit,
    onLockToggle: (Boolean) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = 0) { OverlayPanelTab.entries.size }
    val scope = rememberCoroutineScope()
    val phase = state.runner.phase

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MaaTheme.style.cardElevation,
        shadowElevation = MaaTheme.style.cardElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaaDesignTokens.Overlay.pad),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Overlay.gap),
        ) {
            PanelHeader(
                selectedTab = OverlayPanelTab.entries[pagerState.currentPage],
                onTabSelected = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                isLocked = isLocked,
                onLockToggle = onLockToggle,
                onBackToApp = onBackToApp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
            ) { page ->
                when (OverlayPanelTab.entries[page]) {
                    OverlayPanelTab.TASKS -> OverlayTaskSplit(
                        state = state,
                        onIntent = onIntent,
                    )

                    OverlayPanelTab.LOG -> RunLogPanel(
                        entries = logEntries,
                        onExport = onExportLog,
                        onClear = { onIntent(SessionIntent.ClearRunLog) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Overlay.gap),
            ) {
                OverlayBarButton(
                    onClick = onHide,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.overlay_hide), style = MaterialTheme.typography.labelSmall)
                }
                if (phase.isBusy) {
                    OverlayBarButton(
                        onClick = { onIntent(SessionIntent.Stop) },
                        enabled = phase != RunnerPhase.Stopping,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (phase == RunnerPhase.Stopping) {
                                    R.string.runner_stopping
                                } else {
                                    R.string.runner_stop
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    OverlayBarButton(
                        onClick = { onIntent(SessionIntent.Start(TaskSurface.Overlay)) },
                        enabled = state.canStart,
                        filled = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.runner_start),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private enum class OverlayPanelTab {
    TASKS,
    LOG,
}

@Composable
private fun PanelHeader(
    selectedTab: OverlayPanelTab,
    onTabSelected: (OverlayPanelTab) -> Unit,
    isLocked: Boolean,
    onLockToggle: (Boolean) -> Unit,
    onBackToApp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        OverlayPanelTab.entries.forEach { tab ->
            Text(
                text = stringResource(
                    when (tab) {
                        OverlayPanelTab.TASKS -> R.string.overlay_tab_tasks
                        OverlayPanelTab.LOG -> R.string.overlay_tab_log
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedTab == tab) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.maaClickable(onClick = { onTabSelected(tab) }),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        OverlayIconHit(
            icon = Icons.Outlined.Home,
            contentDescription = stringResource(R.string.overlay_back_to_app),
            onClick = onBackToApp,
        )
        OverlayIconHit(
            icon = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = stringResource(
                if (isLocked) R.string.overlay_unlock else R.string.overlay_lock,
            ),
            onClick = { onLockToggle(!isLocked) },
            tint = if (isLocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
