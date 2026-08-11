package com.aliothmoon.maafw.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.schedule.ScheduleIntent
import com.aliothmoon.maafw.schedule.ScheduleFieldError
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.validationErrors
import com.aliothmoon.maafw.schedule.ScheduleType
import com.aliothmoon.maafw.schedule.ScheduleViewModel
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.clearFocusOnBlankTap
import com.aliothmoon.maafw.ui.components.MaaTimePickerDialog
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.koin.androidx.compose.koinViewModel

/**
 * 规则编辑页（NavHost 推入的二级页面，不再是抽屉）
 *
 * 新建态按现有规则数自增命名（对齐 MaaMeow），用户可改；草稿留本组合，
 * 保存才发 Intent——中途返回等于放弃，与配置编辑一致
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditScreen(
    strategyId: String?,
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isNew = strategyId.isNullOrBlank() || strategyId == NEW_STRATEGY
    // 自增默认名：与 MaaMeow 一致用「现有数 + 1」，避免重名也无须让用户起名
    val defaultName = stringResource(R.string.schedule_default_name, state.rows.size + 1)
    val initial: ScheduleStrategy = remember(strategyId, defaultName, state.rows) {
        if (isNew) {
            ScheduleStrategy(name = defaultName)
        } else {
            // 列表已展示过这条，VM 里必有；极端情况下找不到（被删）退回新建态
            state.rows.firstOrNull { it.strategy.id == strategyId }?.strategy
                ?: ScheduleStrategy(name = defaultName)
        }
    }

    // initial 只作首帧种子：打开后归草稿管，外部再变也不覆盖用户正在改的
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val errors = draft.validationErrors
    var intervalDaysText by remember(initial.id) {
        mutableStateOf(initial.intervalDays?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var intervalHoursText by remember(initial.id) {
        mutableStateOf(initial.intervalHours?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var pickingTimeIndex by remember { mutableStateOf<Int?>(null) }
    var pickingStartDate by remember { mutableStateOf(false) }
    var pickingStartTime by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.schedule_edit_new else R.string.schedule_edit_existing,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                // 保存/删除放顶栏，恒可见；不再压在滚动末尾（对齐 MaaMeow）
                actions = {
                    if (!isNew) {
                        IconButton(onClick = {
                            viewModel.onIntent(ScheduleIntent.Delete(initial.id))
                            onBack()
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.schedule_edit_delete),
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.onIntent(ScheduleIntent.Save(draft.normalized()))
                            onBack()
                        },
                        enabled = errors.isEmpty(),
                    ) {
                        Text(stringResource(R.string.schedule_edit_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .clearFocusOnBlankTap()
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            ITextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = stringResource(R.string.schedule_edit_name),
                placeholder = stringResource(R.string.schedule_edit_name_placeholder),
                // 没错误就传 null：M3 只看这个槽是不是 null，槽里的 composable 什么都不吐
                // 也照样占一行高，无错时下面会空出一条带子
                supportingText = if (ScheduleFieldError.NAME in errors) {
                    { Text(stringResource(R.string.schedule_edit_error_name)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ScheduleSection(stringResource(R.string.schedule_edit_configuration)) {
                if (state.configurations.isEmpty()) {
                    Text(
                        text = stringResource(R.string.schedule_edit_configuration_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // null 是一档真选项而不是缺省兜底：绑死某一份和「跟着我当下用的那份」
                    // 是两种不同的意图，老规则读出来是 null，落在后者上正合适
                    MaaSingleChoiceFlow(
                        options = listOf(
                            null to stringResource(R.string.schedule_edit_configuration_active),
                        ) + state.configurations.map { it.id to it.name },
                        selected = draft.runConfigurationId
                            ?.takeIf { id -> state.configurations.any { it.id == id } },
                        onSelect = { draft = draft.copy(runConfigurationId = it) },
                    )
                }
            }

            ScheduleSection(stringResource(R.string.schedule_edit_type)) {
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
                    errors = errors,
                    onToggleAllDays = {
                        val all = DayOfWeek.entries.toSet()
                        draft = draft.copy(daysOfWeek = if (draft.daysOfWeek == all) emptySet() else all)
                    },
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
                    errors = errors,
                    intervalDaysText = intervalDaysText,
                    intervalHoursText = intervalHoursText,
                    onIntervalDaysChange = { text ->
                        intervalDaysText = text.filter { it.isDigit() }.take(MAX_INTERVAL_DIGITS)
                        draft = draft.copy(intervalDays = intervalDaysText.toIntOrNull())
                    },
                    onIntervalHoursChange = { text ->
                        intervalHoursText = text.filter { it.isDigit() }.take(MAX_INTERVAL_DIGITS)
                        draft = draft.copy(intervalHours = intervalHoursText.toIntOrNull())
                    },
                    onPickStartDate = { pickingStartDate = true },
                    onPickStartTime = { pickingStartTime = true },
                )
            }

            // 排在最后：这一组是「跑起来之后怎么收场」，改的频率远低于上面的时间与配置。
            // 整组是逐条规则的，不是全局设置——对齐 MaaMeow 的归属
            ScheduleSection(stringResource(R.string.schedule_edit_advanced)) {
                ScheduleToggleRow(
                    label = stringResource(R.string.schedule_edit_force_start),
                    tip = stringResource(R.string.schedule_edit_force_start_tip),
                    checked = draft.forceStart,
                    onCheckedChange = { draft = draft.copy(forceStart = it) },
                )
                ScheduleToggleRow(
                    label = stringResource(R.string.schedule_edit_auto_sleep),
                    checked = draft.autoSleepAfterTask,
                    onCheckedChange = { draft = draft.copy(autoSleepAfterTask = it) },
                )
                if (draft.autoSleepAfterTask) {
                    ScheduleToggleRow(
                        label = stringResource(R.string.schedule_edit_skip_sleep_if_awake),
                        checked = draft.skipAutoSleepIfAwake,
                        onCheckedChange = { draft = draft.copy(skipAutoSleepIfAwake = it) },
                    )
                }
                ScheduleToggleRow(
                    label = stringResource(R.string.schedule_edit_close_app),
                    tip = stringResource(R.string.schedule_edit_close_app_tip),
                    checked = draft.closeAppAfterTask,
                    onCheckedChange = { draft = draft.copy(closeAppAfterTask = it) },
                )
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

/** 小节标题 + 内容；替掉 MaaCard 的描边卡，对齐 MaaMeow 的 SectionHeader（轻、不占高） */
@Composable
private fun ScheduleToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tip: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            tip?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MaaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ScheduleSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixedTimeSection(
    draft: ScheduleStrategy,
    errors: Set<ScheduleFieldError>,
    onToggleAllDays: () -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onEditTime: (Int) -> Unit,
    onRemoveTime: (Int) -> Unit,
) {
    ScheduleSection(stringResource(R.string.schedule_edit_days)) {
        // 选中色对齐 MaaMeow：primary 底 + onPrimary 字（非 M3 默认的 secondaryContainer）
        val chipColors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        )
        val allSelected = DayOfWeek.entries.all { it in draft.daysOfWeek }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            FilterChip(
                selected = allSelected,
                onClick = onToggleAllDays,
                label = { Text(stringResource(R.string.schedule_edit_every_day)) },
                colors = chipColors,
            )
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in draft.daysOfWeek,
                    onClick = { onToggleDay(day) },
                    label = { Text(day.shortLabel()) },
                    colors = chipColors,
                )
            }
        }
        if (ScheduleFieldError.DAYS in errors) {
            Text(
                text = stringResource(R.string.schedule_edit_error_days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    ScheduleSection(stringResource(R.string.schedule_edit_times)) {
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            draft.executionTimes.forEachIndexed { index, time ->
                InputChip(
                    selected = false,
                    onClick = { onEditTime(index) },
                    label = { Text(time.format(timeFormatter)) },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemoveTime(index) },
                            modifier = Modifier.size(18.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_delete),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    },
                )
            }
            AssistChip(
                onClick = { onEditTime(draft.executionTimes.size) },
                label = { Text(stringResource(R.string.schedule_edit_add_time)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (ScheduleFieldError.TIMES in errors) {
            Text(
                text = stringResource(R.string.schedule_edit_error_times),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun IntervalSection(
    draft: ScheduleStrategy,
    errors: Set<ScheduleFieldError>,
    intervalDaysText: String,
    intervalHoursText: String,
    onIntervalDaysChange: (String) -> Unit,
    onIntervalHoursChange: (String) -> Unit,
    onPickStartDate: () -> Unit,
    onPickStartTime: () -> Unit,
) {
    ScheduleSection(stringResource(R.string.schedule_edit_start_time)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            // 这两个是「点开选值」的表单项，不是动作按钮：圆角要跟同屏的 ITextField 对齐
            // （两者都取 radii.inner），走按钮的默认档会比旁边的输入框圆一圈
            val fieldShape = MaterialTheme.shapes.extraSmall
            MaaOutlinedButton(
                onClick = onPickStartDate,
                shape = fieldShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(draft.startZoned()?.toLocalDate()?.toString() ?: LocalDate.now().toString())
            }
            MaaOutlinedButton(
                onClick = onPickStartTime,
                shape = fieldShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(draft.startZoned()?.toLocalTime()?.toString() ?: LocalTime.of(DEFAULT_HOUR, 0).toString())
            }
        }
        if (ScheduleFieldError.START in errors) {
            Text(
                text = stringResource(R.string.schedule_edit_error_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    ScheduleSection(stringResource(R.string.schedule_edit_interval)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ITextFieldWithFocus(
                value = intervalDaysText,
                onValueChange = onIntervalDaysChange,
                onFocusLost = {},
                label = stringResource(R.string.schedule_edit_days_unit),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            ITextFieldWithFocus(
                value = intervalHoursText,
                onValueChange = onIntervalHoursChange,
                onFocusLost = {},
                label = stringResource(R.string.schedule_edit_hours_unit),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        val totalMinutes = (draft.intervalDays ?: 0) * 24 * 60 + (draft.intervalHours ?: 0) * 60
        if (totalMinutes > 0) {
            Text(
                text = stringResource(R.string.schedule_edit_total_hours, totalMinutes / 60),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ScheduleFieldError.INTERVAL in errors) {
            Text(
                text = stringResource(R.string.schedule_edit_error_interval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
        intervalDays = null,
        intervalHours = null,
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
private const val NEW_STRATEGY = "new"
