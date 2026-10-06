package com.aliothmoon.maafw.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EyeProtectionDetectorTest {

    private fun detect(
        secure: Set<String> = emptySet(),
        system: Set<String> = emptySet(),
        global: Set<String> = emptySet(),
        service: Boolean = false,
    ) = EyeProtectionDetector.detect(
        secure = { it in secure },
        system = { it in system },
        global = { it in global },
        nightDisplayService = { service },
    )

    @Test
    fun `nothing on`() {
        assertNull(detect())
    }

    @Test
    fun `each vendor key reports its source`() {
        assertEquals("aosp:night_display_activated", detect(secure = setOf("night_display_activated")))
        assertEquals("xiaomi:screen_paper_mode_enabled", detect(system = setOf("screen_paper_mode_enabled")))
        assertEquals("huawei:eyes_protection_mode", detect(system = setOf("eyes_protection_mode")))
        assertEquals("samsung:blue_light_filter", detect(global = setOf("blue_light_filter")))
        assertEquals("oppo:coloros_eyeprotect_enable", detect(system = setOf("eyeprotect_enable")))
        assertEquals("vivo:vivo_night_display", detect(secure = setOf("vivo_night_display")))
    }

    @Test
    fun `the service is only asked when no key hits`() {
        assertEquals("color_display:isNightDisplayActivated", detect(service = true))
        assertEquals(
            "aosp:night_display_activated",
            detect(secure = setOf("night_display_activated"), service = true),
        )
    }
}
