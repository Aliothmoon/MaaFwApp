package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import java.time.LocalTime

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
    // 横屏等矮屏只留三行，保证对话框放得下
    val rows = if (LocalConfiguration.current.screenHeightDp >= 400) 5 else 3

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(MaaDesignTokens.Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.schedule_time_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MaaDesignTokens.Spacing.lg),
                )
                WheelTimePicker(
                    state = state,
                    rows = rows,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MaaDesignTokens.Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                }
            }
        }
    }
}
