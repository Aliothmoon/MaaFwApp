package com.aliothmoon.maafw.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagePolicyTest {

    @Test
    fun `显式英文 locale 映射到英文 PI`() {
        assertEquals("en", AppLanguagePolicy.projectLocaleTag("en-US", "zh-CN"))
    }

    @Test
    fun `跟随英文系统映射到英文 PI`() {
        assertEquals("en", AppLanguagePolicy.projectLocaleTag(null, "en-GB"))
    }

    @Test
    fun `外壳未支持的系统语言回落简中 PI`() {
        assertEquals("zh-CN", AppLanguagePolicy.projectLocaleTag(null, "ja-JP"))
        assertEquals("zh-CN", AppLanguagePolicy.projectLocaleTag(null, "ko-KR"))
        assertEquals("zh-CN", AppLanguagePolicy.projectLocaleTag(null, "zh-Hant-TW"))
    }

    @Test
    fun `显式中文区域不绕过外壳支持范围`() {
        assertEquals("zh-CN", AppLanguagePolicy.projectLocaleTag("zh-TW", "en-US"))
    }
}
