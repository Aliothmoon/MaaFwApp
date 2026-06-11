package com.aliothmoon.maafw.demo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.navigation.ThemeMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DemoPage(
    padding: PaddingValues,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item { SmallTitle(text = "Theme") }
            item { ThemeSection(currentThemeMode, onThemeModeChange) }

            item { SmallTitle(text = "Button") }
            item { ButtonSection() }

            item { SmallTitle(text = "Card") }
            item { CardSection() }

            item { SmallTitle(text = "Switch") }
            item { SwitchSection() }

            item { SmallTitle(text = "Slider") }
            item { SliderSection() }

            item { SmallTitle(text = "ProgressIndicator") }
            item { ProgressSection() }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

@Composable
private fun ThemeSection(
    currentMode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        RadioButtonPreference(
            title = "Light",
            selected = currentMode == ThemeMode.LIGHT,
            onClick = { onModeChange(ThemeMode.LIGHT) },
        )
        RadioButtonPreference(
            title = "Dark",
            selected = currentMode == ThemeMode.DARK,
            onClick = { onModeChange(ThemeMode.DARK) },
        )
        RadioButtonPreference(
            title = "System",
            selected = currentMode == ThemeMode.SYSTEM,
            onClick = { onModeChange(ThemeMode.SYSTEM) },
        )
    }
}

@Composable
private fun ButtonSection() {
    var clickCount by remember { mutableStateOf(0) }
    var submitClickCount by remember { mutableStateOf(0) }

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            text = "Click: $clickCount",
            onClick = { clickCount++ },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        TextButton(
            text = "Submit: $submitClickCount",
            onClick = { submitClickCount++ },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            text = "Disabled",
            onClick = {},
            modifier = Modifier.weight(1f),
            enabled = false,
        )
        Spacer(Modifier.width(12.dp))
        TextButton(
            text = "Disabled",
            onClick = {},
            modifier = Modifier.weight(1f),
            enabled = false,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun CardSection() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = "Default Card",
            style = MiuixTheme.textStyles.title1,
        )
        Text(
            text = "A card with default press feedback",
            style = MiuixTheme.textStyles.body2,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.weight(1f),
            insideMargin = PaddingValues(16.dp),
            onClick = {},
        ) {
            Text(
                text = "Clickable",
                style = MiuixTheme.textStyles.title2,
            )
            Text(
                text = "Sink feedback",
                style = MiuixTheme.textStyles.body2,
            )
        }
        Card(
            modifier = Modifier.weight(1f),
            insideMargin = PaddingValues(16.dp),
            onClick = {},
        ) {
            Text(
                text = "Another",
                style = MiuixTheme.textStyles.title2,
            )
            Text(
                text = "Same style",
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

@Composable
private fun SwitchSection() {
    var switch1 by remember { mutableStateOf(false) }
    var switch2 by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        SwitchPreference(
            title = "Dark Mode",
            summary = "Toggle dark theme",
            checked = switch1,
            onCheckedChange = { switch1 = it },
        )
        SwitchPreference(
            title = "Notifications",
            summary = "Enable push notifications",
            checked = switch2,
            onCheckedChange = { switch2 = it },
        )
        SwitchPreference(
            title = "Disabled",
            checked = true,
            enabled = false,
            onCheckedChange = {},
        )
    }
}

@Composable
private fun SliderSection() {
    var sliderValue by remember { mutableFloatStateOf(0.3f) }
    var stepsValue by remember { mutableFloatStateOf(5f) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        SliderPreference(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            title = "Volume",
            valueText = "${(sliderValue * 100).toInt()}%",
            insideMargin = PaddingValues(16.dp, 16.dp, 16.dp, 0.dp),
        )
        SliderPreference(
            value = stepsValue,
            onValueChange = { stepsValue = it },
            title = "Brightness",
            valueText = "${stepsValue.toInt()}/10",
            valueRange = 0f..10f,
            steps = 9,
            insideMargin = PaddingValues(16.dp, 16.dp, 16.dp, 0.dp),
        )
    }
}

@Composable
private fun ProgressSection() {
    val animatedProgress by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    LinearProgressIndicator(
        progress = animatedProgress,
        modifier = Modifier
            .padding(horizontal = 15.dp)
            .padding(bottom = 12.dp),
    )
    LinearProgressIndicator(
        progress = 0.7f,
        modifier = Modifier
            .padding(horizontal = 15.dp)
            .padding(bottom = 12.dp),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CircularProgressIndicator(progress = animatedProgress)
        CircularProgressIndicator(progress = 0.5f)
        InfiniteProgressIndicator()
    }
}
