package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleRow
import com.aliothmoon.maafw.schedule.ScheduleUiState
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaEmptyState
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 定时 tab：规则列表 + 触发日志入口
 *
 * 编辑与日志都走 sheet，不再开一层全屏——这两块内容都不满一屏，全屏只会多一次进出动画
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onIntent: (ScheduleIntent) -> Unit,
    onEdit: (String?) -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 编辑与日志都进二级页面（NavHost 推入），草稿与日志快照归各自的页面管
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.schedule_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            actions = {
                // 与「系统未允许精确闹钟」卡片上那个按钮同一条路；那张卡片只在被关掉时出现，
                // 允许之后就没别的地方能回到系统开关页了
                if (state.exactAlarmConfigurable) {
                    IconButton(
                        onClick = { onIntent(ScheduleIntent.RequestExactAlarmPermission) },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Alarm,
                            contentDescription = stringResource(
                                R.string.schedule_exact_alarm_settings,
                            ),
                        )
                    }
                }
                IconButton(onClick = onOpenLog) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.schedule_log_title),
                    )
                }
                IconButton(onClick = { onEdit(null) }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.schedule_add),
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
        // 空状态靠 fillParentMaxSize 居中，卡片不能再进列表
        if (!state.exactAlarmAllowed) {
            ExactAlarmCard(
                onGrant = { onIntent(ScheduleIntent.RequestExactAlarmPermission) },
                modifier = Modifier.padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    bottom = MaaDesignTokens.Spacing.md,
                ),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = MaaDesignTokens.Spacing.lg,
                end = MaaDesignTokens.Spacing.lg,
                bottom = MaaDesignTokens.Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            if (state.rows.isEmpty()) {
                // 撑满视口才有多余高度可分；item 默认包裹内容，MaaEmptyState 的居中就无从谈起
                item(key = "empty") { ScheduleEmptyState(Modifier.fillParentMaxSize()) }
            } else {
                items(state.rows, key = { it.strategy.id }) { row ->
                    ScheduleRowCard(
                        row = row,
                        onClick = { onEdit(row.strategy.id) },
                        onToggle = { onIntent(ScheduleIntent.SetEnabled(row.strategy.id, it)) },
                    )
                }
            }
        }
    }

}

@Composable
private fun ExactAlarmCard(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    MaaCard(modifier = modifier, title = stringResource(R.string.schedule_exact_alarm_blocked)) {
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
                    text = strategy.asUiText().asString(),
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
        // 排在停用之前：配置没了是要用户动手的状态，比「已停用」更该先看见
        row.configurationMissing -> MaaToneBadge(
            text = stringResource(R.string.schedule_configuration_missing),
            // 与任务行的「不适用」同款：MaaPalette 没有 error 档，就地取 colorScheme
            tone = MaaTone(
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.errorContainer,
            ),
        )

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
private fun ScheduleEmptyState(modifier: Modifier = Modifier) {
    MaaEmptyState(
        icon = Icons.Outlined.Schedule,
        title = stringResource(R.string.schedule_empty),
        hint = stringResource(R.string.schedule_empty_hint),
        modifier = modifier,
    )
}
