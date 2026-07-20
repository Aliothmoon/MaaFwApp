package com.aliothmoon.maafw.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App 外壳当前只提供英文与简体中文。PI 必须使用同一支持范围，避免跟随未支持的
 * 系统语言时出现「外壳简中、项目内容日/韩/繁中」的混合界面。
 */
object AppLanguagePolicy {
    fun projectLocaleTag(appLocaleTag: String?, systemLocaleTag: String): String {
        val requested = appLocaleTag?.takeIf { it.isNotBlank() } ?: systemLocaleTag
        val language = requested.replace('_', '-').substringBefore('-').lowercase()
        return if (language == "en") "en" else "zh-CN"
    }
}

/**
 * APP 语言的唯一事实来源是平台侧 per-app locale：
 * API 33+ 由系统持久化（与系统设置的应用语言入口互通，manifest 已声明 localeConfig），
 * API 32- 由 appcompat autoStoreLocales 持久化并在 Activity 附着前回放。
 * 不在 DataStore 另存副本，避免两处存储漂移。
 */
object AppLocales {

    /** 当前 App 级 locale tag（如 zh-CN）；null 表示跟随系统。 */
    fun currentTag(): String? = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()

    /** 当前 PI 应使用的语言；与 App 外壳实际支持范围保持一致。 */
    fun currentProjectTag(): String = AppLanguagePolicy.projectLocaleTag(
        appLocaleTag = currentTag(),
        systemLocaleTag = Locale.getDefault().toLanguageTag(),
    )

    /** 切换 App 语言（null 恢复跟随系统），已启动的 Activity 会随之重建。 */
    fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
