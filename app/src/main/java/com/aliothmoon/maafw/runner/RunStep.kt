package com.aliothmoon.maafw.runner

/** 一个挂载物在这一轮里的落点 */
enum class HookOutcome {
    /** 做了，且登记了收尾 */
    ENGAGED,

    /** 本轮不适用（开关没开、模式不对），什么都没做 */
    SKIPPED,

    /** engage 抛了或超时；gating 的会连带中止整轮 */
    FAILED,
}

/**
 * 发起过程里的一步
 *
 * 记 [hookId] 而不是把动作枚举进协议：挂载物是可扩展的，枚举一次就得改一次 schema。
 * id 是我们自己定的稳定字符串，展示时查表拿文案，查不到就直接显示 id
 */
data class RunStep(val hookId: String, val outcome: HookOutcome)

/**
 * 收集发起过程
 *
 * 只覆盖 engage 侧：收尾发生在整轮结束之后（可能是几十分钟后），那时这次发起的记账早写完了。
 * 收尾的过程要看运行日志，不在这里
 */
fun interface RunStepSink {
    fun record(step: RunStep)
}
