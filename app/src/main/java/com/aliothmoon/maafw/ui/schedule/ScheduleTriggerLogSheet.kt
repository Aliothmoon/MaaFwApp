package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.TriggerLogEntry
import com.aliothmoon.maafw.schedule.TriggerResult
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.MaaToneBadge

/** 触发日志：只读，按时间倒序 */
@Composable
fun ScheduleTriggerLogSheet(
    entries: List<TriggerLogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            MaaSheetHeader(
                title = stringResource(R.string.schedule_log_title),
                onClose = onDismiss,
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.schedule_log_clear))
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    // 不给 key：日志是只读快照，行也没有可复用的稳定标识（同一策略可重复出现）
                    items(entries) { entry ->
                        TriggerLogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerLogRow(entry: TriggerLogEntry) {
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
                        TriggerResult.TRIGGERED -> MaaTheme.palette.success
                        else -> MaaTheme.palette.warning
                    },
                )
            }
            Text(
                text = triggerLogTimeUiText(entry.actualAt, entry.scheduledAt).asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
