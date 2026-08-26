package com.aliothmoon.maafw.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DeviceInfoTextTest {

    @Test
    fun `renders all device info sections and fields`() {
        val text = DeviceInfoText.render(info())

        assertTrue("=== MaaFwApp Device & App Info ===" in text)
        assertTrue("--- Device ---" in text)
        assertEquals("Export Time : 2026-08-26 10:30:00.000 (+0800)", text.lines()[2])
        assertTrue("App         : com.example.app" in text.lines())
        assertTrue("Version     : 1.2.3 (45) debug" in text.lines())
        assertTrue("Device      : Google Pixel" in text.lines())
        assertTrue("Android     : 16 (API 36)" in text.lines())
        assertTrue("Screen      : 1920 x 1080 @ 120Hz (density 480dpi)" in text.lines())
        assertTrue("RAM         : 8.0 GB total, 1.5 GB free" in text.lines())
        assertTrue("SELinux     : enforcing" in text.lines())
    }

    @Test
    fun `failed collectors remain readable`() {
        val text = DeviceInfoText.render(info(screen = "unknown", memory = "unknown", selinux = "unknown"))

        assertTrue("Screen      : unknown" in text.lines())
        assertTrue("RAM         : unknown" in text.lines())
        assertTrue("SELinux     : unknown" in text.lines())
    }

    private fun info(
        screen: String = "1920 x 1080 @ 120Hz (density 480dpi)",
        memory: String = "8.0 GB total, 1.5 GB free",
        selinux: String = "enforcing",
    ) = DeviceInfo(
        exportTime = ZonedDateTime.of(2026, 8, 26, 10, 30, 0, 0, ZoneId.of("+08:00")),
        applicationId = "com.example.app",
        versionName = "1.2.3",
        versionCode = 45,
        buildType = "debug",
        device = "Google Pixel",
        android = "16 (API 36)",
        securityPatch = "2026-08-05",
        abi = "arm64-v8a, armeabi-v7a",
        screen = screen,
        memory = memory,
        storage = "20.0 GB usable / 128.0 GB total",
        batteryOptimization = "NOT ignored (schedule may be killed)",
        selinux = selinux,
    )
}
