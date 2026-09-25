package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrain
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import java.time.LocalTime

private val PickerItemHeight = 48.dp

/**
 * 时间选择器弹窗，结果收成 [LocalTime]——调用方不必碰 state 的 hour/minute
 *
 * 用自绘的 [WheelTimePicker] 而非 M3 的 [androidx.compose.material3.TimePicker]：
 * 后者的表盘与键盘输入在不同 Android 版本上表现不一致，且选中行没法跟着主题走。
 * M3 也没给 TimePicker 配套的 Dialog 壳（DatePicker 有），所以这里自己补一层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaaTimePickerDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberWheelTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            TimePickerDialogContent(
                state = state,
                onConfirm = { onConfirm(LocalTime.of(state.hour, state.minute)) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun TimePickerDialogContent(
    state: WheelTimePickerState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SubcomposeLayout(
        modifier = Modifier.padding(MaaDesignTokens.Spacing.xxl),
    ) { constraints ->
        val title = subcompose("title") {
            Text(
                text = stringResource(R.string.schedule_time_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }.single().measure(constraints.copy(minHeight = 0))

        val actions = subcompose("actions") {
            DialogActions(onConfirm = onConfirm, onDismiss = onDismiss)
        }.single().measure(constraints.copy(minHeight = 0))

        val titleGap = MaaDesignTokens.Spacing.lg.roundToPx()
        val actionsGap = MaaDesignTokens.Spacing.md.roundToPx()
        val availablePickerHeight = constraints.maxHeight - title.height - actions.height -
            titleGap - actionsGap
        val rows = visibleRowsFor(availablePickerHeight, itemHeight = PickerItemHeight, density = this)

        val picker = subcompose("picker") {
            WheelTimePicker(
                state = state,
                rows = rows,
                itemHeight = PickerItemHeight,
                modifier = Modifier.fillMaxWidth(),
            )
        }.single().measure(
            constraints.copy(minHeight = 0, maxHeight = availablePickerHeight.coerceAtLeast(0))
        )

        val width = maxOf(title.width, picker.width, actions.width)
        val height = title.height + titleGap + picker.height + actionsGap + actions.height
        val size = constraints.constrain(IntSize(width, height))
        layout(size.width, size.height) {
            title.placeRelative(0, 0)
            picker.placeRelative(0, title.height + titleGap)
            actions.placeRelative(0, title.height + titleGap + picker.height + actionsGap)
        }
    }
}

@Composable
private fun DialogActions(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.dialog_confirm))
        }
    }
}

/** 矮窗口按测量后的剩余空间收缩，替代按屏幕高度估算 */
private fun visibleRowsFor(availableHeight: Int, itemHeight: Dp, density: Density): Int {
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val rows = if (availableHeight <= 0) 1 else availableHeight / itemHeightPx
    return rows.coerceIn(1, 5).let { if (it % 2 == 0) it - 1 else it }
}
