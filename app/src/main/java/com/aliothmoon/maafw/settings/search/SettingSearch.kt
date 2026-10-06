package com.aliothmoon.maafw.settings.search

import android.os.SystemClock
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionSectionState
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromProject
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置搜索（移植自 MaaMeow 的 SettingSearch）
 *
 * 索引手写、锚点手贴：没有代码生成，靠 `SettingSearchContractTest` 扫源码保证
 * 「索引里的每个锚点都真有一个 `SettingSearchTarget` 贴着」。
 * 和 MaaMeow 的区别：FwApp 一半设置来自 PI（`setting[]` 分区与各类 option），
 * 那部分在运行时由 [SettingSearchIndex.projectEntries] 现生成，文案是 PI 自己的，所以标题用 [UiText] 而不是 resId
 */

/** 设置页可折叠分区的定位键；PI 分区的键由 [projectSection] 现拼 */
object SettingsSections {
    const val UPDATE = "settings.update"
    const val DISPLAY = "settings.display"
    const val DURATION = "settings.duration"
    const val NOTIFICATION = "settings.notification"
    const val LOG = "settings.log"
    const val BACKUP = "settings.backup"
    const val PI = "settings.pi"
    const val OTHER = "settings.other"
    const val ABOUT = "settings.about"

    const val PI_GLOBAL = "pi.global"
    const val PI_RESOURCE = "pi.resource"
    const val PI_CONTROLLER = "pi.controller"

    fun projectSection(sectionName: String) = "pi.section.$sectionName"
}

/** 手写条目的锚点；值只要全局唯一，契约测试按常量名去源码里找 `SettingSearchTarget(SettingAnchors.X` */
object SettingAnchors {
    const val AUTO_CHECK_UPDATE = "update.auto_check"
    const val AUTO_DOWNLOAD_UPDATE = "update.auto_download"
    const val THEME = "display.theme"
    const val THEME_STYLE = "display.theme_style"
    const val UI_SCALE = "display.ui_scale"
    const val WALLPAPER = "display.wallpaper"
    const val LANGUAGE = "display.language"
    const val DURATION_LIMIT = "duration.limit"
    const val NOTIFICATION_SETTINGS = "notification.entry"
    const val RUN_LOG_ARCHIVE = "log.archive"
    const val APP_LOG = "log.app"
    const val EXPORT_LOGS = "log.export"
    const val DEBUG_MODE = "log.debug"
    const val SAVE_ON_ERROR = "log.save_on_error"
    const val EXPORT_CONFIG = "backup.export"
    const val IMPORT_CONFIG = "backup.import"
    const val IMPORT_SHARE_CODE = "backup.share_code"
    const val REINSTALL_PI = "pi.reinstall"
    const val BACKEND = "other.backend"
    const val RESOLUTION = "other.resolution"
    const val PIP_ON_HOME = "other.pip"
    const val TELEMETRY = "other.telemetry"
    const val ABOUT = "about.project"

    const val WALLPAPER_ENABLE = "wallpaper.enable"

    const val NOTIFY_LIVE_STYLE = "notify.live_style"
    const val NOTIFY_ENABLE = "notify.enable"
    const val NOTIFY_ON_COMPLETE = "notify.on_complete"
    const val NOTIFY_ON_ERROR = "notify.on_error"
    const val NOTIFY_ON_SERVICE_DIED = "notify.on_service_died"
    const val NOTIFY_LOG_DETAILS = "notify.log_details"

    const val RUN_MODE = "home.run_mode"
    const val OVERLAY_MODE = "home.overlay_mode"
    const val PERMISSIONS = "home.permissions"
    const val FOREGROUND_RESOLUTION = "home.foreground_resolution"
    const val CONTROLLER = "home.controller"
    const val RESOURCE = "home.resource"

    const val SCREEN_SAVER = "tasks.screen_saver"
    const val CLOSE_APP_AFTER_TASK = "tasks.close_app"
    const val TOUCH_PREVIEW = "tasks.touch_preview"

    fun projectOption(scope: String, optionName: String) = "$scope.option.$optionName"
}

/** 条目在哪；[path] 是结果下方那行面包屑 */
sealed interface SettingLocation {
    val path: List<UiText>

    /** 设置页的某个分区：滚过去，可折叠的顺带展开一次 */
    data class Section(val sectionKey: String, val title: UiText) : SettingLocation {
        override val path: List<UiText> get() = listOf(title)
    }

    /** 推入式子页面 */
    data class Page(val route: String, override val path: List<UiText>) : SettingLocation

    /** 首页的某张卡 */
    data class Home(val cardTitle: UiText) : SettingLocation {
        override val path: List<UiText> get() = listOf(uiTextOf(R.string.nav_home), cardTitle)
    }

    /** 任务页启停条上的快捷面板：先切到任务页再把面板打开 */
    data object TasksQuickOptions : SettingLocation {
        override val path: List<UiText> =
            listOf(uiTextOf(R.string.nav_tasks), uiTextOf(R.string.runner_quick_options))
    }
}

/** 条目只在某些条件下才渲染；不满足就别出现在结果里，否则点了定位不到 */
enum class SearchCondition { PIP_SUPPORTED, TELEMETRY_DECLARED, BACKGROUND_MODE, FOREGROUND_MODE, FOREGROUND_ALLOWED, CONTROLLER_CHOICE }

/**
 * @param keywords 空格分隔的同义词，走字符串资源才能跟着语言走
 * @param anchor 复合控件的子项借外层锚点
 */
data class SettingSearchEntry(
    val title: UiText,
    val location: SettingLocation,
    val anchor: String,
    val description: UiText? = null,
    val keywords: UiText? = null,
    val condition: SearchCondition? = null,
)

/** 只收常显项：依赖其它开关才出现的子项定位不到，交给父项 */
object SettingSearchIndex {

    private fun section(key: String, titleRes: Int) = SettingLocation.Section(key, uiTextOf(titleRes))

    private val update = section(SettingsSections.UPDATE, R.string.settings_section_update)
    private val display = section(SettingsSections.DISPLAY, R.string.settings_section_display)
    private val duration = section(SettingsSections.DURATION, R.string.settings_section_duration_limit)
    private val notification = section(SettingsSections.NOTIFICATION, R.string.settings_section_notification)
    private val log = section(SettingsSections.LOG, R.string.settings_section_log)
    private val backup = section(SettingsSections.BACKUP, R.string.settings_section_backup)
    private val pi = section(SettingsSections.PI, R.string.settings_section_pi)
    private val other = section(SettingsSections.OTHER, R.string.settings_section_other)
    private val about = section(SettingsSections.ABOUT, R.string.settings_about)

    private val notificationPage = SettingLocation.Page(
        Routes.NOTIFICATION_SETTINGS,
        listOf(uiTextOf(R.string.nav_settings), uiTextOf(R.string.notification_settings_title)),
    )

    private val wallpaperPage = SettingLocation.Page(
        Routes.WALLPAPER,
        listOf(uiTextOf(R.string.nav_settings), uiTextOf(R.string.wallpaper_title)),
    )

    private fun entry(
        titleRes: Int,
        location: SettingLocation,
        anchor: String,
        descRes: Int? = null,
        keywordsRes: Int? = null,
        condition: SearchCondition? = null,
    ) = SettingSearchEntry(
        title = uiTextOf(titleRes),
        location = location,
        anchor = anchor,
        description = descRes?.let { uiTextOf(it) },
        keywords = keywordsRes?.let { uiTextOf(it) },
        condition = condition,
    )

    val staticEntries: List<SettingSearchEntry> = listOf(
        entry(R.string.settings_update_auto_check, update, SettingAnchors.AUTO_CHECK_UPDATE, keywordsRes = R.string.search_keywords_update),
        entry(R.string.settings_update_auto_download, update, SettingAnchors.AUTO_DOWNLOAD_UPDATE, R.string.settings_update_auto_download_desc),
        entry(R.string.settings_theme, display, SettingAnchors.THEME, keywordsRes = R.string.search_keywords_theme),
        entry(R.string.settings_theme_style, display, SettingAnchors.THEME_STYLE),
        entry(R.string.settings_ui_scale, display, SettingAnchors.UI_SCALE, R.string.settings_ui_scale_desc, R.string.search_keywords_ui_scale),
        entry(R.string.wallpaper_title, display, SettingAnchors.WALLPAPER, R.string.wallpaper_desc, R.string.search_keywords_wallpaper),
        entry(R.string.settings_language, display, SettingAnchors.LANGUAGE, keywordsRes = R.string.search_keywords_language),
        entry(R.string.settings_duration_limit_enabled, duration, SettingAnchors.DURATION_LIMIT, keywordsRes = R.string.search_keywords_duration),
        entry(R.string.notification_settings_title, notification, SettingAnchors.NOTIFICATION_SETTINGS, keywordsRes = R.string.search_keywords_notification),
        entry(R.string.log_archive_title, log, SettingAnchors.RUN_LOG_ARCHIVE, R.string.settings_log_archive_desc),
        entry(R.string.app_log_title, log, SettingAnchors.APP_LOG, R.string.settings_log_error_desc),
        entry(R.string.log_export_title, log, SettingAnchors.EXPORT_LOGS, R.string.settings_log_export_desc),
        entry(R.string.settings_debug_mode, log, SettingAnchors.DEBUG_MODE, R.string.settings_debug_mode_desc),
        entry(R.string.settings_save_on_error, log, SettingAnchors.SAVE_ON_ERROR),
        entry(R.string.config_export_title, backup, SettingAnchors.EXPORT_CONFIG, R.string.config_export_desc, R.string.search_keywords_backup),
        entry(R.string.config_import_title, backup, SettingAnchors.IMPORT_CONFIG, R.string.config_import_desc, R.string.search_keywords_backup),
        entry(R.string.config_import_share_code_title, backup, SettingAnchors.IMPORT_SHARE_CODE, R.string.config_import_share_code_desc, R.string.search_keywords_backup),
        entry(R.string.pi_reinstall_title, pi, SettingAnchors.REINSTALL_PI, R.string.pi_reinstall_desc),
        entry(R.string.permission_backend, other, SettingAnchors.BACKEND, keywordsRes = R.string.search_keywords_backend),
        entry(R.string.settings_resolution, other, SettingAnchors.RESOLUTION, keywordsRes = R.string.search_keywords_resolution),
        entry(R.string.settings_pip_on_home, other, SettingAnchors.PIP_ON_HOME, R.string.settings_pip_on_home_desc, condition = SearchCondition.PIP_SUPPORTED),
        entry(R.string.settings_telemetry, other, SettingAnchors.TELEMETRY, R.string.settings_telemetry_desc, condition = SearchCondition.TELEMETRY_DECLARED),
        entry(R.string.settings_about, about, SettingAnchors.ABOUT, keywordsRes = R.string.search_keywords_about),

        entry(R.string.wallpaper_enable, wallpaperPage, SettingAnchors.WALLPAPER_ENABLE, R.string.wallpaper_desc, R.string.search_keywords_wallpaper),

        entry(R.string.notification_section_live, notificationPage, SettingAnchors.NOTIFY_LIVE_STYLE, keywordsRes = R.string.search_keywords_live),
        entry(R.string.notification_enable, notificationPage, SettingAnchors.NOTIFY_ENABLE),
        entry(R.string.notification_popup, notificationPage, SettingAnchors.NOTIFY_ENABLE),
        entry(R.string.notification_send_on_complete, notificationPage, SettingAnchors.NOTIFY_ON_COMPLETE),
        entry(R.string.notification_send_on_error, notificationPage, SettingAnchors.NOTIFY_ON_ERROR),
        entry(R.string.notification_send_on_service_died, notificationPage, SettingAnchors.NOTIFY_ON_SERVICE_DIED),
        entry(R.string.notification_include_log_details, notificationPage, SettingAnchors.NOTIFY_LOG_DETAILS),

        entry(R.string.settings_run_mode, SettingLocation.Home(uiTextOf(R.string.settings_run_mode)), SettingAnchors.RUN_MODE, keywordsRes = R.string.search_keywords_run_mode, condition = SearchCondition.FOREGROUND_ALLOWED),
        entry(R.string.settings_overlay_mode, SettingLocation.Home(uiTextOf(R.string.settings_overlay_mode)), SettingAnchors.OVERLAY_MODE, keywordsRes = R.string.search_keywords_overlay, condition = SearchCondition.FOREGROUND_MODE),
        entry(R.string.permission_section, SettingLocation.Home(uiTextOf(R.string.permission_section)), SettingAnchors.PERMISSIONS, keywordsRes = R.string.search_keywords_permissions),
        entry(R.string.foreground_resolution_title, SettingLocation.Home(uiTextOf(R.string.foreground_resolution_title)), SettingAnchors.FOREGROUND_RESOLUTION, keywordsRes = R.string.search_keywords_resolution, condition = SearchCondition.FOREGROUND_MODE),
        entry(R.string.settings_controller, SettingLocation.Home(uiTextOf(R.string.settings_controller)), SettingAnchors.CONTROLLER, condition = SearchCondition.CONTROLLER_CHOICE),
        entry(R.string.settings_resource, SettingLocation.Home(uiTextOf(R.string.settings_resource)), SettingAnchors.RESOURCE),

        entry(R.string.settings_screen_saver_auto, SettingLocation.TasksQuickOptions, SettingAnchors.SCREEN_SAVER, keywordsRes = R.string.search_keywords_screen_saver, condition = SearchCondition.BACKGROUND_MODE),
        entry(R.string.quick_setting_close_app_after_task, SettingLocation.TasksQuickOptions, SettingAnchors.CLOSE_APP_AFTER_TASK, condition = SearchCondition.BACKGROUND_MODE),
        entry(R.string.quick_setting_touch_preview, SettingLocation.TasksQuickOptions, SettingAnchors.TOUCH_PREVIEW),
    )

    /**
     * PI 带来的选项：`setting[]` 分区、没进分区的全局选项、资源与控制器选项
     *
     * 只收顶层（depth 0）：子选项要父选项选到某个分支才出现，定位不到
     */
    fun projectEntries(
        sections: List<OptionSectionState>,
        globalOptions: List<OptionEditorState>,
        resourceOptions: List<OptionEditorState>,
        controllerOptions: List<OptionEditorState>,
    ): List<SettingSearchEntry> = buildList {
        val grouped = mutableSetOf<String>()
        sections.forEach { section ->
            val location = SettingLocation.Section(
                SettingsSections.projectSection(section.name),
                uiTextFromProject(section.label),
            )
            section.options.forEach { grouped += it.name }
            // 同一个选项能进好几个分区，锚点跟着分区走，每处各一个靶子
            addOptions(section.options, location, location.sectionKey)
        }
        addOptions(
            globalOptions.filterNot { it.name in grouped },
            SettingLocation.Section(SettingsSections.PI_GLOBAL, uiTextOf(R.string.settings_section_global_option)),
            SettingsSections.PI_GLOBAL,
        )
        addOptions(
            resourceOptions,
            SettingLocation.Section(SettingsSections.PI_RESOURCE, uiTextOf(R.string.settings_section_resource_option)),
            SettingsSections.PI_RESOURCE,
        )
        addOptions(
            controllerOptions,
            SettingLocation.Section(SettingsSections.PI_CONTROLLER, uiTextOf(R.string.settings_section_controller_option)),
            SettingsSections.PI_CONTROLLER,
        )
    }

    private fun MutableList<SettingSearchEntry>.addOptions(
        options: List<OptionEditorState>,
        location: SettingLocation.Section,
        scope: String,
    ) {
        options.filter { it.depth == 0 }.forEach { option ->
            add(
                SettingSearchEntry(
                    title = uiTextFromProject(option.label.ifBlank { option.name }),
                    location = location,
                    anchor = SettingAnchors.projectOption(scope, option.name),
                    description = option.description?.let { uiTextFromProject(it) },
                ),
            )
        }
    }
}

/** 解析好文本的一条；进入搜索才建，换语言时重建 */
data class SearchableSetting(
    val entry: SettingSearchEntry,
    val title: String,
    val description: String,
    val keywords: String,
    val path: String,
) {
    internal val titleLower = title.lowercase()
    internal val haystack = listOf(title, description, keywords, path).joinToString("\n").lowercase()
}

object SettingSearchMatcher {

    /** 空格分词，每个词都要命中；标题直接命中的排前面，其余保持清单顺序 */
    fun filter(items: List<SearchableSetting>, query: String): List<SearchableSetting> {
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return items
            .filter { item -> tokens.all { it in item.haystack } }
            .sortedBy { item -> if (tokens.all { it in item.titleLower }) 0 else 1 }
    }

    private val WHITESPACE = Regex("\\s+")
}

data class SettingSearchRequest(
    val entry: SettingSearchEntry,
    val requestedAtMs: Long,
) {
    /** 锚点迟迟没出现就作废，免得过一会儿突然闪一下 */
    fun isFresh(nowMs: Long = SystemClock.uptimeMillis()): Boolean = nowMs - requestedAtMs <= TTL_MS

    companion object {
        const val TTL_MS = 5_000L
    }
}

/**
 * 跨页定位：结果点下去发一个请求，目标页展开、滚动，锚点高亮后消费掉
 *
 * 进程级单例：请求要跨过 tab 切换与子页面推入，挂在哪个页面的 VM 上都活不到那一头
 */
class SettingSearchNavigator(private val clock: () -> Long = SystemClock::uptimeMillis) {

    private val _pending = MutableStateFlow<SettingSearchRequest?>(null)
    val pending: StateFlow<SettingSearchRequest?> = _pending.asStateFlow()

    fun request(entry: SettingSearchEntry) {
        _pending.value = SettingSearchRequest(entry, clock())
    }

    /** 只消费自己那一个：新请求已经顶上来时不能把它清掉 */
    fun consume(request: SettingSearchRequest) {
        _pending.compareAndSet(request, null)
    }
}
