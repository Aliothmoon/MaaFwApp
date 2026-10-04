package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.domain.RemoteBackend

/** 一条规则加上它算出来的下次触发时刻；后者不落盘，每次由闹钟规则现算 */
data class ScheduleRow(
    val strategy: ScheduleStrategy,
    /** null = 规则不完整（没选星期或没填时刻）、已停用，或绑定的配置已被删 */
    val nextTriggerAt: Long?,
    /** 绑定的运行配置已不存在；到点也不跑，等用户重新绑一份 */
    val configurationMissing: Boolean = false,
)

/** 编辑页「跑哪份配置」的候选；只要 id 与名字，不碰 PI */
data class ScheduleConfigurationOption(
    val id: String,
    val name: String,
)

data class ScheduleUiState(
    val rows: List<ScheduleRow> = emptyList(),
    /** 可绑定的运行配置；空 = 用户还没建过 */
    val configurations: List<ScheduleConfigurationOption> = emptyList(),
    /** 新建规则时预选它；用户当下在用的那份是最可能的意图 */
    val activeConfigurationId: String? = null,
    /** 系统是否允许精确闹钟；否则退到 setAlarmClock，状态栏会多个闹钟图标 */
    val exactAlarmAllowed: Boolean = true,
    /** 系统有没有精确闹钟开关页（API 31+）；没有就别摆那个入口 */
    val exactAlarmConfigurable: Boolean = false,
    val triggerLog: List<TriggerLogEntry> = emptyList(),
    /** 调度环境未通过项；空 = 健康卡不出现 */
    val healthIssues: List<ScheduleHealthIssue> = emptyList(),
    /** 健康卡上「XX 未授权」要说出是哪个后端 */
    val backend: RemoteBackend = RemoteBackend.SHIZUKU,
    /**
     * 保存后待引导的项；空 = 不弹
     *
     * 跟着环境现算：用户从系统页回来，授好的那项自己消失，不用逐项确认
     */
    val setupWizard: List<ScheduleHealthIssue> = emptyList(),
)

sealed interface ScheduleIntent {
    /** id 已存在即更新，否则新增 */
    data class Save(val strategy: ScheduleStrategy) : ScheduleIntent
    data class Delete(val strategyId: String) : ScheduleIntent
    data class DeleteTriggerLogEntry(val stableId: String) : ScheduleIntent
    data class SetEnabled(val strategyId: String, val enabled: Boolean) : ScheduleIntent

    data object LoadTriggerLog : ScheduleIntent
    data object ClearTriggerLog : ScheduleIntent

    /** 拉起系统的精确闹钟设置页；要 Context，转成 Effect */
    data object RequestExactAlarmPermission : ScheduleIntent

    /** 从系统设置页回来后重读精确闹钟与锁屏方式；这两项都没有变更回调 */
    data object RefreshEnvironment : ScheduleIntent

    /** 保存了一条启用的规则：把没满足的前提逐项引导一遍 */
    data class RequestSetupWizard(val strategyId: String) : ScheduleIntent

    data object DismissSetupWizard : ScheduleIntent
}

sealed interface ScheduleEffect {
    /** 精确闹钟设置页要 Context 拉起，交给 Route 层 */
    data object RequestExactAlarmPermission : ScheduleEffect
}
