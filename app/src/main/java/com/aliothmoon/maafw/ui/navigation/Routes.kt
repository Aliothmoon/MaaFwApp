package com.aliothmoon.maafw.ui.navigation

/**
 * 二级页面路由
 *
 * 主 tab（Home/Tasks/Schedule/Settings）由 AppRoot 的 HorizontalPager 承载，
 * 不进 NavHost；NavHost 只承载推入式子页面，主 tab 路由仅作空占位
 */
object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val SCHEDULE = "schedule"
    const val SETTINGS = "settings"

    /** 主 tab 路由集合，用来判断当前是否停在主界面 */
    val mainTabs: Set<String> = setOf(HOME, TASKS, SCHEDULE, SETTINGS)

    /** 定时编辑；strategyId 取 "new" 表示新建，省略也按新建处理 */
    const val SCHEDULE_EDIT_NEW = "schedule_edit?strategyId=new"
    const val SCHEDULE_EDIT = "schedule_edit?strategyId={strategyId}"
    const val SCHEDULE_EDIT_ARG = "strategyId"
    fun scheduleEdit(strategyId: String) = "schedule_edit?strategyId=$strategyId"

    /** 定时触发日志 */
    const val SCHEDULE_TRIGGER_LOG = "schedule_trigger_log"

    /** 历史运行日志列表 */
    const val RUN_LOG_ARCHIVE = "run_log_archive"

    /** 某一份历史日志的正文；file 是 `RunSessionLogFile.fileName` */
    const val RUN_LOG_DETAIL = "run_log_detail/{file}"
    const val RUN_LOG_DETAIL_ARG = "file"
    fun runLogDetail(fileName: String) = "run_log_detail/$fileName"

    /** 通知设置：系统事件通知档位 + 外部推送渠道 */
    const val NOTIFICATION_SETTINGS = "notification_settings"

    /** 错误日志（app 自身的警告与错误） */
    const val APP_LOG = "app_log"

    /** 某一份错误日志的正文；file 是 `AppLogFileInfo.name` */
    const val APP_LOG_DETAIL = "app_log_detail/{file}"
    const val APP_LOG_DETAIL_ARG = "file"
    fun appLogDetail(fileName: String) = "app_log_detail/$fileName"
}
