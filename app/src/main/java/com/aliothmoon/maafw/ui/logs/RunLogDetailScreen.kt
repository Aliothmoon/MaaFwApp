package com.aliothmoon.maafw.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.log.RunLogDetailViewModel
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.RunSessionOutcome
import com.aliothmoon.maafw.runner.RunSessionRecord
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.runLogColor
import org.koin.androidx.compose.koinViewModel

/**
 * 一份历史日志的正文（二级页面）
 *
 * 正文是写入时就渲染好的成品文本，这里不再翻译——历史记录用当时的语言，
 * 与 `RunSessionRecord.Line` 的取舍一致
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
    viewModel: RunLogDetailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(fileName) { viewModel.load(fileName) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.log_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(MaaDesignTokens.Spacing.lg),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = stringResource(
                        if (state.loading) R.string.common_loading else R.string.log_detail_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        // 一次只展开一条：detail 展开就是十来行，多条同时展开这个列表没法看
        var expanded by remember { mutableStateOf(-1) }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
        ) {
            // 索引当 key：同一份文件是只读快照，行序不会变
            itemsIndexed(state.records) { index, record ->
                when (record) {
                    is RunSessionRecord.Header -> HeaderBlock(record)
                    is RunSessionRecord.Footer -> FooterBlock(record)
                    is RunSessionRecord.Line -> LineRow(
                        line = record,
                        expanded = expanded == index,
                        onToggle = { expanded = if (expanded == index) -1 else index },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(header: RunSessionRecord.Header) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MaaDesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.log_detail_started, logTimestamp(header.startedAt)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.log_detail_tasks, header.tasks.joinToString("、")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun FooterBlock(footer: RunSessionRecord.Footer) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaaDesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.log_detail_ended, logTimestamp(footer.endedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            MaaToneBadge(
                text = stringResource(footer.outcome.labelRes()),
                tone = when (footer.outcome) {
                    RunSessionOutcome.COMPLETED -> MaaTheme.palette.success
                    RunSessionOutcome.COMPLETED_WITH_FAILURES,
                    RunSessionOutcome.CANCELLED,
                    RunSessionOutcome.NOT_RUN,
                    -> MaaTheme.palette.warning

                    // 调色板没有 danger 一档，就地拿 M3 的 error 对：整轮失败要与「部分失败」分得开
                    RunSessionOutcome.FAILED -> MaaTone(
                        content = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.errorContainer,
                    )
                },
            )
        }
    }
}

@Composable
private fun LineRow(
    line: RunSessionRecord.Line,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line.detail != null) Modifier.maaClickable(onClick = onToggle) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Text(
            text = logTimeOfDay(line.atMillis),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (line.kind == RunLogKind.Focus) {
                MaaMarkdown(
                    text = line.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = runLogColor(line.kind),
                )
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.labelSmall,
                    // 与运行日志面板同一条规矩：原始转储保持等宽，合成过的按正文排版
                    fontFamily = if (line.kind == RunLogKind.Verbose) FontFamily.Monospace else null,
                    color = runLogColor(line.kind),
                )
            }
            if (expanded) {
                Text(
                    text = line.detail.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xxs),
                )
            }
        }
    }
}

private fun RunSessionOutcome.labelRes(): Int = when (this) {
    RunSessionOutcome.COMPLETED -> R.string.log_outcome_completed
    RunSessionOutcome.COMPLETED_WITH_FAILURES -> R.string.log_outcome_completed_with_failures
    RunSessionOutcome.CANCELLED -> R.string.log_outcome_cancelled
    RunSessionOutcome.FAILED -> R.string.log_outcome_failed
    RunSessionOutcome.NOT_RUN -> R.string.log_outcome_not_run
}
