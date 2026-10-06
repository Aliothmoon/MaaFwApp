package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.runner.WakeUnlockHook

/** 触发日志上「去修复」把人送去哪 */
enum class ScheduleFixAction {
    /** 定时页右上角的「唤醒解锁」：换解锁方式、补 PIN、重录手势 */
    WAKE_UNLOCK_SETTINGS,

    /** 电源管理白名单：前台服务被系统拦下多半是它 */
    BATTERY,

    /** 规则绑定的运行配置没了，回编辑页重新选一份 */
    EDIT_RULE,
}

/**
 * 触发结局 → 修复入口（对齐 MaaMeow 的 ExecutionFixMapping）
 *
 * 只收能一步修好的：项目没加载好、已有执行在跑这类等一会儿自己就好，不给按钮；
 * 被前置检查拦下的原因五花八门，日志里那行原文比一个泛泛的入口更有用
 */
object ScheduleFixMapping {

    fun fixActionFor(entry: TriggerLogEntry): ScheduleFixAction? = when {
        // 排最前：解锁是 gating，失败时结局只是笼统的 BLOCKED，要看步骤才认得出
        entry.steps.any { it.hookId == WakeUnlockHook.ID && it.outcome == TriggerStepOutcome.FAILED } ->
            ScheduleFixAction.WAKE_UNLOCK_SETTINGS

        entry.result == TriggerResult.FAILED_SERVICE_START -> ScheduleFixAction.BATTERY

        entry.failureReason == TriggerFailureReason.CONFIGURATION_MISSING -> ScheduleFixAction.EDIT_RULE

        else -> null
    }
}
