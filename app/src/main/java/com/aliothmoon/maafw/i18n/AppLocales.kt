package com.aliothmoon.maafw.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** 外壳仅 en/zh-CN；PI 同步此范围，避免外壳与项目语言混用 */
object AppLanguagePolicy {
    fun projectLocaleTag(appLocaleTag: String?, systemLocaleTag: String): String {
        val requested = appLocaleTag?.takeIf { it.isNotBlank() } ?: systemLocaleTag
        val language = requested.replace('_', '-').substringBefore('-').lowercase()
        return if (language == "en") "en" else "zh-CN"
    }
}

/** 便于 ViewModel 单测注入 */
fun interface LocaleController {
    fun apply(tag: String?)
}

/**
 * 事实来源为平台 per-app locale，不进 DataStore
 * API 33+ 系统持久化；API 32- appcompat autoStoreLocales
 */
object AppLocales : LocaleController {

    /** null = 跟随系统 */
    fun currentTag(): String? = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()

    fun currentProjectTag(): String = AppLanguagePolicy.projectLocaleTag(
        appLocaleTag = currentTag(),
        systemLocaleTag = Locale.getDefault().toLanguageTag(),
    )

    /** null 恢复跟随系统；已启动 Activity 会重建 */
    override fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
