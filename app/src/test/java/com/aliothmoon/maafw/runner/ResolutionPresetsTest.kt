package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionPresetsTest {

    @Test
    fun `内置两档是 720P@240 与 1080P@280`() {
        assertEquals(
            listOf("1280x720@240", "1920x1080@280"),
            ResolutionPresets.builtIn.map { it.id },
        )
    }

    /** 没配 profile 时 BuildConfig 给空数组，列表就是内置两档，第一项是默认 */
    @Test
    fun `未配置时用内置预设`() {
        assertEquals(ResolutionPresets.builtIn, ResolutionPresets.available)
        assertEquals(ResolutionPresets.builtIn.first(), ResolutionPresets.default)
    }

    @Test
    fun `按 id 取预设，找不到或为空落到默认档`() {
        assertEquals(ResolutionPresets.builtIn[1], ResolutionPresets.resolve("1920x1080@280"))
        assertEquals(ResolutionPresets.default, ResolutionPresets.resolve(""))
        assertEquals(ResolutionPresets.default, ResolutionPresets.resolve(null))
        // 同尺寸换了 dpi 就是另一档：profile 改了 dpi，旧选择回到默认
        assertEquals(ResolutionPresets.default, ResolutionPresets.resolve("1920x1080@320"))
    }

    @Test
    fun `拆配方编码，拆不开的跳过`() {
        val parsed = ResolutionPresets.parse(listOf("2K|2560|1440|320", "broken", "x|1|a|2"))
        assertEquals(listOf(ResolutionPreset("2K", DisplayResolution(2560, 1440), 320)), parsed)
    }
}
