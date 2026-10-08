package com.aliothmoon.maafw.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

/**
 * 在 Activity 之外把 [UiText] 渲染成成品文本
 *
 * 不能直接拿 Application context 解析：API 33 起系统 LocaleManager 会把 per-app locale
 * 铺到整个进程，32 及以下 appcompat 只改 Activity 的 Configuration，Application 那份
 * 仍是系统语言——落进日志文件的就成了用户没选的那一种
 *
 * 按语言标签缓存：运行日志一行调一次，每次现建 Configuration context 太贵
 */
class LocalizedTextRenderer(private val base: Context) {

    private val lock = Any()
    private var cachedTag: String? = null
    private var cached: Context? = null

    fun render(text: UiText): String = text.resolve(localizedContext())

    private fun localizedContext(): Context = synchronized(lock) {
        val tag = AppLocales.currentTag()
        cached?.takeIf { cachedTag == tag }
            ?: build(tag).also {
                cachedTag = tag
                cached = it
            }
    }

    /** 跟随系统时 [base] 那份本来就是对的，不必再包一层 */
    private fun build(tag: String?): Context {
        if (tag == null) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
