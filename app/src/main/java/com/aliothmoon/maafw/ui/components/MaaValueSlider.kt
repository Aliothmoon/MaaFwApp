package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

/**
 * 标签 + 当前值 + 滑块
 *
 * 拖动只改本地值，松手才 [onValueCommit]：每一帧都落盘会把 DataStore 写爆，
 * 落盘后的回流还会和手指抢位置。要跟手预览的调用方接 [onDrag]
 */
@Composable
fun MaaValueSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueText: (Int) -> String = { "$it%" },
    onDrag: (Int) -> Unit = {},
) {
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { sliderValue = value.toFloat() }
    val current = sliderValue.roundToInt().coerceIn(range)
    Column(modifier = modifier.fillMaxWidth()) {
        MaaLabeledControlRow(
            label = label,
            trailing = {
                Text(
                    text = valueText(current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onDrag(it.roundToInt().coerceIn(range))
            },
            onValueChangeFinished = { onValueCommit(sliderValue.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
