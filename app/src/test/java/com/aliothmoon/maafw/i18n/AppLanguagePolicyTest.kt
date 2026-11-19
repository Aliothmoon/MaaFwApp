package com.aliothmoon.maafw.i18n

import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val zh = LocaleListCompat.forLanguageTags("zh-CN")
    private val en = LocaleListCompat.forLanguageTags("en")
    private val none = LocaleListCompat.getEmptyLocaleList()

    @Test
    fun `33 起 Application 资源交给系统`() {
        assertNull(AppLanguagePolicy.localesToSync(33, appLocales = en, systemLocales = zh, current = zh))
    }

    @Test
    fun `32 及以下把应用语言同步进 Application 资源`() {
        assertEquals(en, AppLanguagePolicy.localesToSync(32, appLocales = en, systemLocales = zh, current = zh))
        assertEquals(zh, AppLanguagePolicy.localesToSync(28, appLocales = zh, systemLocales = en, current = en))
    }

    /** 改回跟随系统：之前写进去的应用语言要换回系统那份 */
    @Test
    fun `跟随系统时换回系统语言 已一致则不动`() {
        assertEquals(zh, AppLanguagePolicy.localesToSync(30, appLocales = none, systemLocales = zh, current = en))
        assertNull(AppLanguagePolicy.localesToSync(30, appLocales = en, systemLocales = zh, current = en))
    }

    /** 没有 Activity 的冷启动 appcompat 读到空：32 及以下用备份，33 起空就是跟随系统 */
    @Test
    fun `AndroidX 为空时只在 32 及以下用备份`() {
        assertEquals("en", AppLanguagePolicy.resolveTag(30, androidx = null, backup = "en"))
        assertNull(AppLanguagePolicy.resolveTag(30, androidx = null, backup = ""))
        assertNull(AppLanguagePolicy.resolveTag(33, androidx = null, backup = "en"))
        assertEquals("zh-CN", AppLanguagePolicy.resolveTag(30, androidx = "zh-CN", backup = "en"))
    }
}
