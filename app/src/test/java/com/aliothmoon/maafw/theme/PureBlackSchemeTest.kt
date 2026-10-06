package com.aliothmoon.maafw.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PureBlackSchemeTest {

    @Test
    fun `pure black flattens page and card surfaces in both styles`() {
        ThemeStyle.entries.forEach { style ->
            val scheme = colorSchemeOf(style, dark = true, pureBlack = true)
            assertEquals(Color.Black, scheme.background)
            assertEquals(Color.Black, scheme.surface)
            assertEquals(Color.Black, scheme.surfaceContainerLow)
            // 卡片描边与对话框底色不跟着压黑，否则分不出层
            assertNotEquals(Color.Black, scheme.outline)
            assertNotEquals(Color.Black, scheme.surfaceContainerHigh)
        }
    }

    @Test
    fun `pure black only applies to the dark scheme`() {
        ThemeStyle.entries.forEach { style ->
            assertEquals(colorSchemeOf(style, dark = false), colorSchemeOf(style, dark = false, pureBlack = true))
            assertNotEquals(Color.Black, colorSchemeOf(style, dark = true).background)
        }
    }
}
