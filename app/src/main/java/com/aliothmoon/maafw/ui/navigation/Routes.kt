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
}
