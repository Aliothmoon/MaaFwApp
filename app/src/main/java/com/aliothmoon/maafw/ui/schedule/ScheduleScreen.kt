package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleRow
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 定时 tab：规则列表 + 触发日志入口
 *
 * 编辑与日志都走 sheet，不再开一层全屏——这两块内容都不满一屏，全屏只会多一次进出动画
 */
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onIntent: (ScheduleIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 编辑草稿是页面临时状态，不进 ScheduleUiState（docs/android-ui-contract.md §12）
    var editing by remember { mutableStateOf<ScheduleStrategy?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScheduleHeader(
            onAdd = {
                editing = ScheduleStrategy(name = "")
                editingIsNew = true
            },
            onOpenLog = {
                onIntent(ScheduleIntent.LoadTriggerLog)
                showLog = true
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MaaDesignTokens.Spacing.lg,
                end = MaaDesignTokens.Spacing.lg,
                bottom = MaaDesignTokens.Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            if (!state.exactAlarmAllowed) {
                item(key = "exact-alarm") {
                    ExactAlarmCard(
                        onGrant = { onIntent(ScheduleIntent.RequestExactAlarmPermission) },
                    )
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") { ScheduleEmptyState() }
            } else {
                items(state.rows, key = { it.strategy.id }) { row ->
                    ScheduleRowCard(
                        row = row,
                        onClick = {
                            editing = row.strategy
                            editingIsNew = false
                        },
                        onToggle = { onIntent(ScheduleIntent.SetEnabled(row.strategy.id, it)) },
                    )
                }
            }
        }
    }

    editing?.let { strategy ->
        ScheduleEditSheet(
            initial = strategy,
            isNew = editingIsNew,
            onSave = {
                onIntent(ScheduleIntent.Save(it))
                editing = null
            },
            onDelete = {
                onIntent(ScheduleIntent.Delete(strategy.id))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (showLog) {
        ScheduleTriggerLogSheet(
            entries = state.triggerLog,
            onClear = { onIntent(ScheduleIntent.ClearTriggerLog) },
            onDismiss = { showLog = false },
        )
    }
}

@Composable
private fun ScheduleHeader(onAdd: () -> Unit, onOpenLog: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaaDesignTokens.Spacing.lg,
                end = MaaDesignTokens.Spacing.sm,
                top = MaaDesignTokens.Spacing.lg,
                bottom = MaaDesignTokens.Spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.schedule_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenLog) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = stringResource(R.string.schedule_log_title),
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.schedule_add),
            )
        }
    }
}

@Composable
private fun ExactAlarmCard(onGrant: () -> Unit) {
    MaaCard(title = stringResource(R.string.schedule_exact_alarm_blocked)) {
        Text(
            text = stringResource(R.string.schedule_exact_alarm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onGrant) {
            Text(stringResource(R.string.schedule_exact_alarm_grant))
        }
    }
}

@Composable
private fun ScheduleRowCard(
    row: ScheduleRow,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val strategy = row.strategy
    MaaCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(MaaDesignTokens.Card.innerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(
                    text = strategy.name.ifBlank { stringResource(R.string.schedule_edit_name_placeholder) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = strategy.ruleSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NextTriggerLine(row)
            }
            MaaSwitch(checked = strategy.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun NextTriggerLine(row: ScheduleRow) {
    when {
        !row.strategy.enabled -> MaaToneBadge(
            text = stringResource(R.string.schedule_disabled),
            tone = MaaTheme.palette.neutral,
        )

        row.nextTriggerAt == null -> MaaToneBadge(
            text = stringResource(R.string.schedule_next_none),
            tone = MaaTheme.palette.warning,
        )

        else -> Text(
            text = stringResource(R.string.schedule_next_trigger, formatTriggerTime(row.nextTriggerAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ScheduleEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MaaDesignTokens.IconSize.xl),
        )
        Text(
            text = stringResource(R.string.schedule_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.schedule_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
