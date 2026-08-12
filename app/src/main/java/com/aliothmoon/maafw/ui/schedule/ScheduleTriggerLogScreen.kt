package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.schedule.TriggerLogEntry
import com.aliothmoon.maafw.schedule.TriggerResult
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import org.koin.androidx.compose.koinViewModel

/**
 * 触发日志页（二级页面）：只读，按时间倒序
 *
 * 进页即触发一次加载；日志是落盘快照，留在 ScheduleViewModel 里复用主会话的 StateFlow
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTriggerLogScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onIntent(ScheduleIntent.LoadTriggerLog) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.schedule_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state.triggerLog.isNotEmpty()) {
                        TextButton(onClick = { viewModel.onIntent(ScheduleIntent.ClearTriggerLog) }) {
                            Text(stringResource(R.string.schedule_log_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.triggerLog.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(MaaDesignTokens.Spacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.schedule_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(MaaDesignTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                // 不给 key：日志是只读快照，行没有稳定标识（同一策略可重复出现）
                items(state.triggerLog, key = { it.stableId }) { entry ->
                    TriggerLogRow(entry, onDelete = { viewModel.onIntent(ScheduleIntent.DeleteTriggerLogEntry(entry.stableId)) })
                }
            }
        }
    }
}

@Composable
private fun TriggerLogRow(entry: TriggerLogEntry, onDelete: () -> Unit) {
    MaaCardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Card.innerPadding),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                Text(
                    text = entry.strategyName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                MaaToneBadge(
                    text = entry.result.asUiText().asString(),
                    tone = when (entry.result) {
                        TriggerResult.STARTED, TriggerResult.TRIGGERED -> MaaTheme.palette.success
                        else -> MaaTheme.palette.warning
                    },
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.common_delete),
                        modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                    )
                }
            }
            Text(
                text = triggerLogTimeUiText(entry.actualAt, entry.scheduledAt).asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「昨晚为什么没跑」是查这份日志的头号问题，别只留一个「未能开始」
            val why = entry.detail?.takeIf { it.isNotBlank() }
                ?: entry.failureReason?.asUiText()?.asString()
            why?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
