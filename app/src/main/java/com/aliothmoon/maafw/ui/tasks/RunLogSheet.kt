package com.aliothmoon.maafw.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志
 *
 * 正文一律是 MaaFramework 的原始字符串，不翻译也不清洗——这里是排障面，
 * 加工过的文本对不上官方文档与源码就失去了价值
 */
@Composable
internal fun RunLogSheet(
    entries: List<RunLogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaSheetHeader(title = stringResource(R.string.run_log_title), onClose = onDismiss)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.run_log_count, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                    Text(stringResource(R.string.run_log_clear))
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.run_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val listState = rememberLazyListState()
            // 新行到了就跟到底；日志面板默认看最新的那几条
            LaunchedEffect(entries.lastOrNull()?.id) {
                listState.animateScrollToItem(entries.lastIndex)
            }
            val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                items(entries, key = { it.id }) { entry ->
                    RunLogRow(entry = entry, time = formatter.format(Date(entry.atMillis)))
                }
            }
        }
    }
}

@Composable
private fun RunLogRow(entry: RunLogEntry, time: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // PI 模板正文按协议支持 Markdown 与 HTML 子集，等宽直出会把标签露给用户
        if (entry.kind == RunLogKind.Focus) {
            MaaMarkdown(
                text = entry.text,
                style = MaterialTheme.typography.labelSmall,
                color = entry.kind.color(),
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = entry.kind.color(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 只靠颜色分级；正文不加前缀，免得挤掉本来就长的原始字符串 */
@Composable
private fun RunLogKind.color(): Color = when (this) {
    RunLogKind.Progress -> MaterialTheme.colorScheme.primary
    RunLogKind.Observation -> MaterialTheme.colorScheme.onSurface
    RunLogKind.Malformed -> MaterialTheme.colorScheme.error
    RunLogKind.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    RunLogKind.Log -> MaterialTheme.colorScheme.onSurfaceVariant
    // 唯一一条写给用户看的，颜色要压得住满屏灰字
    RunLogKind.Focus -> MaterialTheme.colorScheme.onSurface
}
