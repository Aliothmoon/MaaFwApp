package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunMode

/** 谁发起的这一轮；决定「需要确认」时有没有人可问 */
sealed interface RunTrigger {
    data object Manual : RunTrigger
    data class Schedule(val strategyId: String) : RunTrigger
}

/** 一次确认的身份；检查靠它认出「这条问过了，用户点了头」 */
@JvmInline
value class ConfirmToken(val value: String)

/**
 * 一轮运行的冻结输入，与 [RunPlan] 同生命周期
 *
 * 条件判断只读它，不许现取外部状态：engage 时开关开着盖了屏保、用户中途关掉，
 * 收尾再去读开关就判成「没开」，屏保永远撤不掉
 *
 * 挂载物自己那份配置不进这里——[RunEnvHook.engage] 只在 Start 时刻跑一次，
 * 在里面读到的就已经是冻结值，捕获进闭包即可；塞进来只会让本类随挂载物数量膨胀
 */
class RunContext(
    val trigger: RunTrigger,
    val runMode: RunMode,
    val plan: RunPlan,
    /** 用户已点头的项；检查靠它跳过自己，否则确认完重跑会再问一遍 */
    val acknowledged: Set<ConfirmToken> = emptySet(),
)
