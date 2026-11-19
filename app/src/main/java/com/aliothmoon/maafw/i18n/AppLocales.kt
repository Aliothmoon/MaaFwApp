package com.aliothmoon.maafw.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** 外壳仅 en/zh-CN；PI 同步此范围 */
object AppLanguagePolicy {
    fun projectLocaleTag(appLocaleTag: String?, systemLocaleTag: String): String {
        val requested = appLocaleTag?.takeIf { it.isNotBlank() } ?: systemLocaleTag
        val language = requested.replace('_', '-').substringBefore('-').lowercase()
        return if (language == "en") "en" else "zh-CN"
    }

    /**
     * API 32 及以下 Application 的 Resources 该换成哪种语言；null = 不用动
     *
     * 33 起系统把 per-app locale 铺到整个进程，不归这里管
     */
    fun localesToSync(
        sdkInt: Int,
        appLocales: LocaleListCompat,
        systemLocales: LocaleListCompat,
        current: LocaleListCompat,
    ): LocaleListCompat? {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return null
        val target = appLocales.takeUnless { it.isEmpty } ?: systemLocales
        return target.takeUnless { it == current }
    }

    /**
     * 应用语言：AndroidX 那份为准，32 及以下它还没从存储里读回来时才用 [backup]
     *
     * appcompat 只在 Activity 建起来时读回存的语言，定时、开机这类没有 Activity 的冷启动读到的是空
     */
    fun resolveTag(sdkInt: Int, androidx: String?, backup: String?): String? = when {
        androidx != null -> androidx
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> null
        else -> backup?.takeIf { it.isNotBlank() }
    }
}

fun interface LocaleController {
    fun apply(tag: String?)
}

/**
 * 事实来源为平台 per-app locale，不进 DataStore
 * API 33+ 系统持久化；API 32- appcompat autoStoreLocales
 *
 * 32 及以下另存一份到 SharedPreferences，只给没有 Activity 的冷启动兜底（[AppLanguagePolicy.resolveTag]）
 */
object AppLocales : LocaleController {

    private const val PREFS = "app_locale_backup"
    private const val KEY_TAG = "tag"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Application.onCreate 里最先调：之后渲染的通知、悬浮窗才是应用语言 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncApplicationResources(context)
    }

    /** null = 跟随系统 */
    fun currentTag(): String? = AppLanguagePolicy.resolveTag(
        sdkInt = Build.VERSION.SDK_INT,
        androidx = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag(),
        backup = prefs?.getString(KEY_TAG, null),
    )

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
        prefs?.edit { putString(KEY_TAG, tag.orEmpty()) }
    }

    /**
     * Activity 建好后 appcompat 已读回存的语言，抄一份进备份：
     * 备份是这一版才有的，之前在 32 及以下选过语言的用户要靠这一步补上
     */
    fun backupFromAndroidx() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag().orEmpty()
        val store = prefs ?: return
        if (store.getString(KEY_TAG, null) != tag) store.edit { putString(KEY_TAG, tag) }
    }

    /**
     * 32 及以下 appcompat 只改 Activity 的 Configuration：Application 那份还是系统语言，
     * Service 的通知、悬浮窗、屏保、Toast、通知渠道名都会露系统语言。系统配置一变就被重置回去，
     * 所以 onConfigurationChanged 里也要再同步一次
     */
    @Suppress("DEPRECATION")
    fun syncApplicationResources(context: Context) {
        val res = context.applicationContext.resources
        val target = AppLanguagePolicy.localesToSync(
            sdkInt = Build.VERSION.SDK_INT,
            appLocales = currentTag()?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList(),
            systemLocales = ConfigurationCompat.getLocales(Resources.getSystem().configuration),
            current = ConfigurationCompat.getLocales(res.configuration),
        ) ?: return
        val config = Configuration(res.configuration)
        ConfigurationCompat.setLocales(config, target)
        res.updateConfiguration(config, res.displayMetrics)
    }
}
