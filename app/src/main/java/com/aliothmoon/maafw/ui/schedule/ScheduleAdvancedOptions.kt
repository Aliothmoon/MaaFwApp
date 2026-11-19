package com.aliothmoon.maafw.ui.schedule

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.schedule.CloseAppEffect
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.CardCollapse
import com.aliothmoon.maafw.ui.components.CardExpand
import com.aliothmoon.maafw.ui.components.ExpandableTipContent
import com.aliothmoon.maafw.ui.components.ExpandableTipIcon
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch

private enum class AdvancedTab(@StringRes val label: Int) {
    TRIGGER(R.string.schedule_edit_advanced_tab_trigger),
    RUNNING(R.string.schedule_edit_advanced_tab_running),
    FINISH(R.string.schedule_edit_advanced_tab_finish),
}

/**
 * 定时规则的高级选项，按触发时 / 运行期间 / 结束后分页（对齐 MaaMeow）
 *
 * 页签用分组页签同款的单选胶囊；各页按最高的那页定高，切页时下方内容不跳
 */
@Composable
internal fun ScheduleAdvancedOptions(
    draft: ScheduleStrategy,
    runMode: RunMode,
    globalCloseAppAfterTask: Boolean,
    onDraftChange: (ScheduleStrategy) -> Unit,
    /** 打开关屏幕电源前要确认：屏幕全黑、要按电源键才亮，误开了会以为手机死机 */
    onRequestScreenPowerOff: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AdvancedTab.TRIGGER) }
    // 只在后台生效的项，前台模式下补一行
    val foregroundNote = if (runMode == RunMode.FOREGROUND) {
        stringResource(R.string.schedule_edit_close_app_effect_foreground)
    } else {
        null
    }
    val closeEffect = CloseAppEffect.of(runMode, globalCloseAppAfterTask, draft.closeAppAfterTask)

    MaaSingleChoiceFlow(
        options = AdvancedTab.entries.map { it to stringResource(it.label) },
        selected = tab,
        onSelect = { tab = it },
    )
    TallestPage(selected = tab.ordinal) {
        Column {
            ScheduleToggleRow(
                label = stringResource(R.string.schedule_edit_force_start),
                tip = stringResource(R.string.schedule_edit_force_start_tip),
                checked = draft.forceStart,
                onCheckedChange = { onDraftChange(draft.copy(forceStart = it)) },
            )
        }
        Column {
            ScheduleToggleRow(
                label = stringResource(R.string.schedule_edit_hardware_screen_off),
                tip = stringResource(R.string.schedule_edit_hardware_screen_off_tip),
                checked = draft.hardwareScreenOff,
                onCheckedChange = { on ->
                    if (on) onRequestScreenPowerOff() else onDraftChange(draft.copy(hardwareScreenOff = false))
                },
                note = foregroundNote,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
            // 子开关和父开关包成一组，间距放进子项自己的 padding：留在外层 spacedBy 里，
            // 那 8dp 会在展开开始时一下冒出来、收起结束时一下消失，看着是一跳
            Column {
                ScheduleToggleRow(
                    label = stringResource(R.string.schedule_edit_auto_sleep),
                    tip = stringResource(R.string.schedule_edit_auto_sleep_tip),
                    checked = draft.autoSleepAfterTask,
                    onCheckedChange = { onDraftChange(draft.copy(autoSleepAfterTask = it)) },
                )
                SubOption(visible = draft.autoSleepAfterTask) {
                    ScheduleToggleRow(
                        label = stringResource(R.string.schedule_edit_skip_sleep_if_awake),
                        tip = stringResource(R.string.schedule_edit_skip_sleep_if_awake_tip),
                        checked = draft.skipAutoSleepIfAwake,
                        onCheckedChange = { onDraftChange(draft.copy(skipAutoSleepIfAwake = it)) },
                    )
                }
            }
            // 被全局开关接管或前台不生效时才提示，其余看开关即可
            ScheduleToggleRow(
                label = stringResource(R.string.schedule_edit_close_app),
                tip = stringResource(R.string.schedule_edit_close_app_tip),
                checked = draft.closeAppAfterTask,
                onCheckedChange = { onDraftChange(draft.copy(closeAppAfterTask = it)) },
                note = when (closeEffect) {
                    CloseAppEffect.GLOBAL_OVERRIDE -> stringResource(R.string.schedule_edit_close_app_effect_global)
                    CloseAppEffect.FOREGROUND_INACTIVE -> foregroundNote
                    else -> null
                },
                noteHighlighted = closeEffect == CloseAppEffect.GLOBAL_OVERRIDE,
            )
            ScheduleToggleRow(
                label = stringResource(R.string.schedule_edit_collapse),
                tip = stringResource(R.string.schedule_edit_collapse_tip),
                checked = draft.collapseAfterTask,
                onCheckedChange = { onDraftChange(draft.copy(collapseAfterTask = it)) },
            )
        }
    }
}

/** 各页都量一遍按最高的定高，只摆选中那页 */
@Composable
private fun TallestPage(selected: Int, pages: @Composable () -> Unit) {
    Layout(content = pages) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, placeables.maxOf { it.height }) {
            placeables[selected].place(0, 0)
        }
    }
}

/**
 * 一行开关：说明收进标题旁的小 i，点开才在下方展开
 *
 * 标题行只放标题与开关，有没有说明都和开关居中对齐
 */
@Composable
private fun ScheduleToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tip: String? = null,
    /** 标题下常显的一行，只在开关的实际效果与看上去不一致时给 */
    note: String? = null,
    noteHighlighted: Boolean = false,
) {
    var tipExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                // fill = false：标题短时小 i 紧跟标题，长了才换行而不把小 i 挤出去
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tip != null) {
                    ExpandableTipIcon(expanded = tipExpanded, onExpandedChange = { tipExpanded = it })
                }
            }
            MaaSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (noteHighlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (tip != null) {
            ExpandableTipContent(visible = tipExpanded, topSpacing = MaaDesignTokens.Spacing.xs) {
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 从属开关：左侧竖线标出层级，父项关掉就收起 */
@Composable
private fun SubOption(visible: Boolean, content: @Composable () -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    AnimatedVisibility(visible = visible, enter = CardExpand, exit = CardCollapse) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MaaDesignTokens.Spacing.sm, top = MaaDesignTokens.Spacing.sm)
                .drawBehind {
                    drawLine(
                        color = lineColor,
                        start = Offset.Zero,
                        end = Offset(0f, size.height),
                        strokeWidth = MaaDesignTokens.Border.marker.toPx(),
                    )
                }
                .padding(start = MaaDesignTokens.Spacing.lg),
        ) {
            content()
        }
    }
}
