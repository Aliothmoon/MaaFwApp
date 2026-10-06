package com.aliothmoon.maafw.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {

    @Test
    fun `360dp and wider recommends 95`() {
        listOf(360, 393, 411, 600, 0).forEach { assertEquals(95, UiScale.recommended(it, 1f)) }
    }

    @Test
    fun `narrower than 360dp lerps down to 90`() {
        assertEquals(90, UiScale.recommended(320, 1f))
        assertEquals(90, UiScale.recommended(280, 1f))
        assertEquals(93, UiScale.recommended(344, 1f))
        assertEquals(94, UiScale.recommended(352, 1f))
    }

    @Test
    fun `large system font only nudges narrow screens`() {
        assertEquals(95, UiScale.recommended(411, 1.8f))
        assertEquals(85, UiScale.recommended(320, 1.5f))
        assertEquals(88, UiScale.recommended(344, 1.5f))
    }

    @Test
    fun `parse falls back to auto on anything out of range`() {
        assertEquals(UiScale.AUTO, UiScale.parse("auto"))
        assertEquals(UiScale.AUTO, UiScale.parse("0"))
        assertEquals(UiScale.AUTO, UiScale.parse("oops"))
        assertEquals(UiScale.AUTO, UiScale.parse("200"))
        assertEquals(80, UiScale.parse("80"))
        assertEquals(110, UiScale.parse("110"))
    }

    @Test
    fun `format round trips through parse`() {
        assertEquals("auto", UiScale.format(UiScale.AUTO))
        assertEquals("100", UiScale.format(100))
        assertEquals("110", UiScale.format(150))
        listOf(UiScale.AUTO, 80, 95, 110).forEach { assertEquals(it, UiScale.parse(UiScale.format(it))) }
    }

    @Test
    fun `resolve uses the stored value unless auto`() {
        assertEquals(100, UiScale.resolve(100, 320, 1f))
        assertEquals(90, UiScale.resolve(UiScale.AUTO, 320, 1f))
    }

    @Test
    fun `overlay font scale is clamped`() {
        assertEquals(0.85f, UiScale.clampOverlayFontScale(0.5f), 0f)
        assertEquals(1f, UiScale.clampOverlayFontScale(1f), 0f)
        assertEquals(1.3f, UiScale.clampOverlayFontScale(2f), 0f)
    }
}
