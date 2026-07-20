package com.aliothmoon.maafw.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * APP 语言的唯一事实来源是平台侧 per-app locale：
 * API 33+ 由系统持久化（与系统设置的应用语言入口互通，manifest 已声明 localeConfig），
 * API 32- 由 appcompat autoStoreLocales 持久化并在 Activity 附着前回放。
 * 不在 DataStore 另存副本，避免两处存储漂移。
 */
object AppLocales {

    /** 当前 App 级 locale tag（如 zh-CN）；null 表示跟随系统。 */
    fun currentTag(): String? = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()

    /** 切换 App 语言（null 恢复跟随系统），已启动的 Activity 会随之重建。 */
    fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
