package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.RunDurationLimit
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaSwitchRow

private val MINUTE_INPUT_MAX_LENGTH = RunDurationLimit.MAX_MINUTES.toString().length

/** 分钟数在任务受理时冻结，运行中改的只影响下一轮，所以控件不跟着运行锁定 */
@Composable
internal fun RunDurationCard(
    state: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val saved = state.runDurationLimitMinutes
    // 失焦才钳位提交：逐键钳位会让人刚敲下「8」就被改成别的数。以已存值为 key，读盘晚到也能同步进来
    var draft by remember(saved) { mutableStateOf(saved.toString()) }

    MaaCard(title = stringResource(R.string.settings_section_duration_limit), collapsible = true) {
        MaaSwitchRow(
            label = stringResource(R.string.settings_duration_limit_enabled),
            checked = state.runDurationLimitEnabled,
            onCheckedChange = { onSettingsIntent(SettingsIntent.SetRunDurationLimitEnabled(it)) },
        )
        if (state.runDurationLimitEnabled) {
            ITextFieldWithFocus(
                value = draft,
                onValueChange = { draft = it },
                onFocusLost = {
                    // 清空后离开算放弃修改，回到已存值，不回落默认值
                    val committed = draft.toIntOrNull()?.let(RunDurationLimit::normalize) ?: saved
                    draft = committed.toString()
                    if (committed != saved) {
                        onSettingsIntent(SettingsIntent.SetRunDurationLimitMinutes(committed))
                    }
                },
                label = stringResource(R.string.settings_duration_limit_minutes),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                inputFilter = { it.length <= MINUTE_INPUT_MAX_LENGTH && it.all(Char::isDigit) },
                supportingText = {
                    Text(
                        text = stringResource(
                            R.string.settings_duration_limit_hint,
                            RunDurationLimit.MIN_MINUTES,
                            RunDurationLimit.MAX_MINUTES,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }
}
