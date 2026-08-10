package com.aliothmoon.maafw.schedule

/** 一条规则加上它算出来的下次触发时刻；后者不落盘，每次由闹钟规则现算 */
data class ScheduleRow(
    val strategy: ScheduleStrategy,
    /** null = 规则不完整（没选星期或没填时刻）或已停用 */
    val nextTriggerAt: Long?,
)

data class ScheduleUiState(
    val rows: List<ScheduleRow> = emptyList(),
    /** 系统是否允许精确闹钟；否则退到 setAlarmClock，状态栏会多个闹钟图标 */
    val exactAlarmAllowed: Boolean = true,
    val triggerLog: List<TriggerLogEntry> = emptyList(),
)

sealed interface ScheduleIntent {
    /** id 已存在即更新，否则新增 */
    data class Save(val strategy: ScheduleStrategy) : ScheduleIntent
    data class Delete(val strategyId: String) : ScheduleIntent
    data class SetEnabled(val strategyId: String, val enabled: Boolean) : ScheduleIntent

    data object LoadTriggerLog : ScheduleIntent
    data object ClearTriggerLog : ScheduleIntent

    /** 拉起系统的精确闹钟设置页；要 Context，转成 Effect */
    data object RequestExactAlarmPermission : ScheduleIntent

    /** 从那个设置页回来后重读；这一项没有变更回调 */
    data object RefreshExactAlarmPermission : ScheduleIntent
}

sealed interface ScheduleEffect {
    /** 精确闹钟设置页要 Context 拉起，交给 Route 层 */
    data object RequestExactAlarmPermission : ScheduleEffect
}
