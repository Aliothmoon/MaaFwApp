package com.aliothmoon.maafw.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.i18n.asUiText

/**
 * 悬浮控制面板
 *
 * 前台模式下这是唯一能停任务的地方——app 界面被目标应用盖住了，回不去。
 * 内容刻意压到最少：状态、进度、停止、回 app。要改配置就点「回 app」，别在浮窗里重做一套
 */
@Composable
fun OverlayPanel(
    state: RunnerState,
    isLocked: Boolean,
    onStop: () -> Unit,
    onBackToApp: () -> Unit,
    onLockToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MaaTheme.style.cardElevation,
        shadowElevation = MaaTheme.style.cardElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaaTheme.style.cardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            PanelHeader(isLocked, onLockToggle, onClose)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                Text(
                    text = state.phase.asUiText().asString(),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.activeExecution?.let { execution ->
                    execution.currentTaskName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (execution.totalTaskCount > 0) {
                        Text(
                            text = "${execution.completedTaskCount}/${execution.totalTaskCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = {
                                execution.completedTaskCount.toFloat() / execution.totalTaskCount
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                state.latestResult?.let {
                    Text(
                        text = it.asUiText().asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                MaaButton(
                    onClick = onBackToApp,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Home, contentDescription = null, Modifier.size(MaaDesignTokens.IconSize.sm))
                    Text(
                        text = stringResource(R.string.overlay_back_to_app),
                        modifier = Modifier.padding(start = MaaDesignTokens.Spacing.xs),
                    )
                }
                MaaButton(
                    onClick = onStop,
                    enabled = state.phase.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.runner_stop))
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    isLocked: Boolean,
    onLockToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.overlay_panel_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // 锁住即禁止拖拽：面板压在目标应用上，误拖会把它拽出可视区
        IconButton(onClick = { onLockToggle(!isLocked) }) {
            Icon(
                imageVector = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = stringResource(
                    if (isLocked) R.string.overlay_unlock else R.string.overlay_lock,
                ),
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}
