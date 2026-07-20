package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(
    name = "Phone default font",
    widthDp = 393,
    heightDp = 360,
    fontScale = 1f,
    showBackground = true,
)
@Preview(
    name = "Phone large font",
    widthDp = 393,
    heightDp = 440,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun AdaptiveTextLayoutScreenshot() {
    MaaFwTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            MaaCard(
                title = "Sell items that exceed the exchange limit",
                trailing = {
                    MaaSwitch(
                        checked = false,
                        onCheckedChange = {},
                    )
                },
            ) {}

            MaaCard(modifier = Modifier.width(329.dp)) {
                MaaSingleChoiceFlow(
                    options = listOf(
                        "system" to "System default",
                        "zh" to "简体中文",
                        "en" to "English",
                    ),
                    selected = "en",
                    onSelect = {},
                )
            }
        }
    }
}
