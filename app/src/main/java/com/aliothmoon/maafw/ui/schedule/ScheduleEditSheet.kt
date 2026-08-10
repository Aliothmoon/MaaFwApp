package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleType
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaMultiChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaTimePickerDialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 规则编辑
 *
 * 草稿全程留在本组合里，只有点保存才发 Intent——中途关掉 sheet 等于放弃，
 * 与配置页那几个 sheet 的行为一致
 */
@Composable
fun ScheduleEditSheet(
    initial: ScheduleStrategy,
    isNew: Boolean,
    onSave: (ScheduleStrategy) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // initial 只作首帧种子：sheet 一旦打开就归草稿管，外部再变也不该覆盖用户正在改的东西
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var intervalText by remember(initial.id) {
        mutableStateOf(initial.intervalMinutes?.toString().orEmpty())
    }
    var pickingTimeIndex by remember { mutableStateOf<Int?>(null) }
    var pickingStartDate by remember { mutableStateOf(false) }
    var pickingStartTime by remember { mutableStateOf(false) }

    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        Column(
            modifier = sheetModifier.imePadding(),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            MaaSheetHeader(
                title = stringResource(
                    if (isNew) R.string.schedule_edit_new else R.string.schedule_edit_existing,
                ),
                onClose = onDismiss,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.schedule_edit_name)) },
                    placeholder = { Text(stringResource(R.string.schedule_edit_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                MaaCard(title = stringResource(R.string.schedule_edit_type)) {
                    MaaSingleChoiceFlow(
                        options = listOf(
                            ScheduleType.FIXED_TIME to stringResource(R.string.schedule_edit_type_fixed),
                            ScheduleType.INTERVAL to stringResource(R.string.schedule_edit_type_interval),
                        ),
                        selected = draft.scheduleType,
                        onSelect = { draft = draft.copy(scheduleType = it) },
                    )
                }

                when (draft.scheduleType) {
                    ScheduleType.FIXED_TIME -> FixedTimeSection(
                        draft = draft,
                        onToggleDay = { day ->
                            val next = if (day in draft.daysOfWeek) draft.daysOfWeek - day else draft.daysOfWeek + day
                            draft = draft.copy(daysOfWeek = next)
                        },
                        onEditTime = { pickingTimeIndex = it },
                        onRemoveTime = { index ->
                            draft = draft.copy(
                                executionTimes = draft.executionTimes.filterIndexed { i, _ -> i != index },
                            )
                        },
                    )

                    ScheduleType.INTERVAL -> IntervalSection(
                        draft = draft,
                        intervalText = intervalText,
                        onIntervalTextChange = { text ->
                            intervalText = text.filter { it.isDigit() }.take(MAX_INTERVAL_DIGITS)
                            draft = draft.copy(intervalMinutes = intervalText.toIntOrNull())
                        },
                        onPickStartDate = { pickingStartDate = true },
                        onPickStartTime = { pickingStartTime = true },
                    )
                }

                MaaCard {
                    MaaLabeledControlRow(
                        label = stringResource(R.string.schedule_edit_enabled),
                        trailing = {
                            MaaSwitch(
                                checked = draft.enabled,
                                onCheckedChange = { draft = draft.copy(enabled = it) },
                            )
                        },
                    )
                }

                if (!draft.isComplete) {
                    Text(
                        text = stringResource(R.string.schedule_edit_incomplete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaaDesignTokens.Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                if (!isNew) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.schedule_edit_delete))
                    }
                }
                OutlinedButton(
                    onClick = { onSave(draft.normalized()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.schedule_edit_save))
                }
            }
        }
    }

    pickingTimeIndex?.let { index ->
        MaaTimePickerDialog(
            initial = draft.executionTimes.getOrNull(index) ?: LocalTime.of(DEFAULT_HOUR, 0),
            onConfirm = { time ->
                val times = draft.executionTimes.toMutableList()
                if (index in times.indices) times[index] = time else times.add(time)
                draft = draft.copy(executionTimes = times.distinct().sorted())
                pickingTimeIndex = null
            },
            onDismiss = { pickingTimeIndex = null },
        )
    }

    if (pickingStartTime) {
        MaaTimePickerDialog(
            initial = draft.startZoned()?.toLocalTime() ?: LocalTime.of(DEFAULT_HOUR, 0),
            onConfirm = { time ->
                val date = draft.startZoned()?.toLocalDate() ?: LocalDate.now()
                draft = draft.copy(startTimeMs = date.atTime(time).atZone(ZoneId.systemDefault()).toEpochMs())
                pickingStartTime = false
            },
            onDismiss = { pickingStartTime = false },
        )
    }

    if (pickingStartDate) {
        StartDateDialog(
            initialMs = draft.startTimeMs,
            onConfirm = { dateMs ->
                val time = draft.startZoned()?.toLocalTime() ?: LocalTime.of(DEFAULT_HOUR, 0)
                // DatePicker 给的是 UTC 当日零点，只能取它的日期部分，不能直接当本地时间戳用
                val date = Instant.ofEpochMilli(dateMs).atZone(ZoneId.of("UTC")).toLocalDate()
                draft = draft.copy(startTimeMs = date.atTime(time).atZone(ZoneId.systemDefault()).toEpochMs())
                pickingStartDate = false
            },
            onDismiss = { pickingStartDate = false },
        )
    }
}

@Composable
private fun FixedTimeSection(
    draft: ScheduleStrategy,
    onToggleDay: (DayOfWeek) -> Unit,
    onEditTime: (Int) -> Unit,
    onRemoveTime: (Int) -> Unit,
) {
    MaaCard(title = stringResource(R.string.schedule_edit_days)) {
        MaaMultiChoiceFlow(
            options = DayOfWeek.entries.map { it to it.shortLabel() },
            selected = draft.daysOfWeek,
            onToggle = onToggleDay,
        )
    }
    MaaCard(title = stringResource(R.string.schedule_edit_times)) {
        if (draft.executionTimes.isEmpty()) {
            Text(
                text = stringResource(R.string.schedule_edit_no_times),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            draft.executionTimes.forEachIndexed { index, time ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onEditTime(index) }, modifier = Modifier.weight(1f)) {
                        Text(
                            text = time.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = { onRemoveTime(index) }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.common_delete),
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { onEditTime(draft.executionTimes.size) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.schedule_edit_add_time))
        }
    }
}

@Composable
private fun IntervalSection(
    draft: ScheduleStrategy,
    intervalText: String,
    onIntervalTextChange: (String) -> Unit,
    onPickStartDate: () -> Unit,
    onPickStartTime: () -> Unit,
) {
    MaaCard(title = stringResource(R.string.schedule_edit_start_time)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            OutlinedButton(onClick = onPickStartDate, modifier = Modifier.weight(1f)) {
                Text(draft.startZoned()?.toLocalDate()?.toString() ?: LocalDate.now().toString())
            }
            OutlinedButton(onClick = onPickStartTime, modifier = Modifier.weight(1f)) {
                Text(draft.startZoned()?.toLocalTime()?.toString() ?: LocalTime.of(DEFAULT_HOUR, 0).toString())
            }
        }
    }
    MaaCard(title = stringResource(R.string.schedule_edit_interval)) {
        OutlinedTextField(
            value = intervalText,
            onValueChange = onIntervalTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateDialog(
    initialMs: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let(onConfirm) ?: onDismiss() },
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** 保存前收口：名称留空给个占位、时刻去重排序，间隔模式清掉固定时刻的残留字段（反之亦然） */
private fun ScheduleStrategy.normalized(): ScheduleStrategy = when (scheduleType) {
    ScheduleType.FIXED_TIME -> copy(
        name = name.trim(),
        executionTimes = executionTimes.distinct().sorted(),
        startTimeMs = null,
        intervalMinutes = null,
    )

    ScheduleType.INTERVAL -> copy(
        name = name.trim(),
        daysOfWeek = emptySet(),
        executionTimes = emptyList(),
    )
}

private fun ScheduleStrategy.startZoned() =
    startTimeMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }

private fun java.time.ZonedDateTime.toEpochMs(): Long = toInstant().toEpochMilli()

private const val DEFAULT_HOUR = 8
private const val MAX_INTERVAL_DIGITS = 5
