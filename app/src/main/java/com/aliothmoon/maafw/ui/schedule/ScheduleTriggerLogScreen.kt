package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.schedule.ScheduleFixAction
import com.aliothmoon.maafw.schedule.ScheduleFixMapping
import com.aliothmoon.maafw.schedule.ScheduleHealthIssue
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.schedule.TriggerFailureReason
import com.aliothmoon.maafw.schedule.TriggerLogEntry
import com.aliothmoon.maafw.schedule.TriggerResult
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaEmptyState
import com.aliothmoon.maafw.ui.components.MaaIconButton
import com.aliothmoon.maafw.ui.components.MaaPromptDialog
import com.aliothmoon.maafw.ui.components.maaClickable
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 触发日志页（二级页面）：只读，按日期成组倒序
 *
 * 进页即触发一次加载；日志是落盘快照，留在 ScheduleViewModel 里复用主会话的 StateFlow
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTriggerLogScreen(
    onBack: () -> Unit,
    /** 去哪修由 Route 层定：要切 tab、要走 Session 的授权、要开编辑页，这一页都够不着 */
    onFix: (ScheduleFixAction, TriggerLogEntry) -> Unit = { _, _ -> },
    viewModel: ScheduleViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ruleIds = remember(state.rows) { state.rows.mapTo(HashSet()) { it.strategy.id } }
    // 后端已就绪时 BACKEND 无事可做（RequestRemoteAccess 会直接返回 true 毫无反应），不摆空操作按钮（对齐 MaaMeow）
    val backendReady = ScheduleHealthIssue.BACKEND_DOWN !in state.healthIssues &&
        ScheduleHealthIssue.BACKEND !in state.healthIssues
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.onIntent(ScheduleIntent.LoadTriggerLog) }

    val entries = state.triggerLog
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(entries) { LocalDate.now(zone) }
    val grouped = remember(entries, zone) {
        entries.groupBy { Instant.ofEpochMilli(it.actualAt).atZone(zone).toLocalDate() }
            .entries
            .toList()
    }

    if (showClearConfirm) {
        MaaPromptDialog(
            title = stringResource(R.string.schedule_log_clear_title),
            message = stringResource(R.string.schedule_log_clear_message),
            icon = MaaIcons.Delete,
            confirmText = stringResource(R.string.schedule_log_clear),
            dismissText = stringResource(R.string.dialog_cancel),
            onConfirm = {
                showClearConfirm = false
                viewModel.onIntent(ScheduleIntent.ClearTriggerLog)
            },
            onDismissRequest = { showClearConfirm = false },
            dismissOnOutsideClick = true,
        )
    }

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
                            imageVector = MaaIcons.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text(
                                text = stringResource(R.string.schedule_log_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (entries.isEmpty()) {
                MaaEmptyState(
                    icon = MaaIcons.History,
                    title = stringResource(R.string.schedule_log_empty),
                    hint = stringResource(R.string.schedule_log_empty_hint),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MaaDesignTokens.Spacing.lg,
                        end = MaaDesignTokens.Spacing.lg,
                        top = MaaDesignTokens.Spacing.xs,
                        bottom = MaaDesignTokens.Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    grouped.forEach { (date, dayEntries) ->
                        item(key = "day-$date") {
                            TriggerLogDayGroup(
                                dateLabel = triggerLogDateHeaderUiText(date, today).asString(),
                                entries = dayEntries,
                                ruleIds = ruleIds,
                                backendReady = backendReady,
                                onDelete = { viewModel.onIntent(ScheduleIntent.DeleteTriggerLogEntry(it.stableId)) },
                                onFix = onFix,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 同一天的记录收进一张白卡内按细分隔线排，与设置页及运行日志的卡片节奏一致 */
@Composable
private fun TriggerLogDayGroup(
    dateLabel: String,
    entries: List<TriggerLogEntry>,
    ruleIds: Set<String>,
    backendReady: Boolean,
    onDelete: (TriggerLogEntry) -> Unit,
    onFix: (ScheduleFixAction, TriggerLogEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaaDesignTokens.Spacing.xs),
        )
        MaaCardSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = MaaDesignTokens.Separator.thickness,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = MaaDesignTokens.Card.innerPadding),
                        )
                    }
                    // 规则已删或后端已就绪时无事可修，不摆空操作按钮
                    val fix = ScheduleFixMapping.fixActionFor(entry)
                        ?.takeUnless { it == ScheduleFixAction.EDIT_RULE && entry.strategyId !in ruleIds }
                        ?.takeUnless { it == ScheduleFixAction.BACKEND && backendReady }
                    TriggerLogRow(
                        entry = entry,
                        onDelete = { onDelete(entry) },
                        onFix = fix?.let { { onFix(it, entry) } },
                    )
                }
            }
        }
    }
}

/** 单一语义色信号：与首页服务状态「● 就绪」同形态，克制不堆色块 */
@Composable
private fun triggerStatusColor(entry: TriggerLogEntry): Color = when {
    entry.result == TriggerResult.STARTED || entry.result == TriggerResult.TRIGGERED ->
        MaaTheme.palette.success.content

    entry.result == TriggerResult.DUPLICATE ->
        MaterialTheme.colorScheme.onSurfaceVariant

    entry.failureReason in WarningReasons ->
        MaaTheme.palette.warning.content

    else -> MaterialTheme.colorScheme.error
}

private val WarningReasons = setOf(
    TriggerFailureReason.BLACKLISTED,
    TriggerFailureReason.MISSED,
    TriggerFailureReason.MISSED_REBOOTED,
    TriggerFailureReason.EXPIRED,
)

@Composable
private fun TriggerLogRow(
    entry: TriggerLogEntry,
    onDelete: () -> Unit,
    onFix: (() -> Unit)?,
) {
    val statusColor = triggerStatusColor(entry)
    val delayText = triggerLogDelayUiText(entry.actualAt, entry.scheduledAt)?.asString()
    // 「昨晚为什么没跑」是查这份日志的头号问题，别只留一个「未能开始」
    val why = entry.detail?.takeIf { it.isNotBlank() }
        ?: entry.failureReason?.asUiText()?.asString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MaaDesignTokens.Spacing.md),
    ) {
        // 右侧删除钮自带 48dp 触控区，右边距收到 Spacing.xs（§3 触控目标视觉补偿）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaaDesignTokens.Card.innerPadding,
                    end = MaaDesignTokens.Spacing.xs,
                    top = MaaDesignTokens.Spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = MaaDesignTokens.Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    Text(
                        text = entry.strategyName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MaaDesignTokens.IconSize.dotSm)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Text(
                            text = entry.result.asUiText().asString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    Text(
                        text = formatTriggerClock(entry.actualAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (delayText != null) {
                        Text(
                            text = delayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaaTheme.palette.warning.content,
                        )
                    }
                }
            }
            MaaIconButton(onClick = onDelete) {
                Icon(
                    imageVector = MaaIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
        }
        if (why != null || onFix != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaaDesignTokens.Card.innerPadding,
                        end = MaaDesignTokens.Card.innerPadding,
                        top = MaaDesignTokens.Spacing.xs,
                    ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                if (why != null) {
                    Text(
                        text = why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (onFix != null) {
                    Text(
                        text = stringResource(R.string.schedule_health_fix),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaaTheme.style.radii.inner))
                            .maaClickable(pressScale = false, onClick = onFix)
                            .semantics { role = Role.Button }
                            .padding(
                                horizontal = MaaDesignTokens.Spacing.xs,
                                vertical = MaaDesignTokens.Spacing.xxs,
                            ),
                    )
                }
            }
        }
    }
}
