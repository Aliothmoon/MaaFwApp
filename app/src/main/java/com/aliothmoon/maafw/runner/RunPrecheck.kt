package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText

/**
 * 启动前的一道闸门
 *
 * **必须是纯函数**：确认循环靠重跑实现（用户点头 → token 进 [RunContext.acknowledged]
 * → 整套检查重跑），有副作用就会被重复施加。写日志也算副作用——日志由编排层按终局记一次，
 * 检查自己不写
 *
 * 零副作用还换来两件事：确认期间环境变了（用户盯着框，特权进程断了）能被重跑当场发现；
 * 同一套检查可以拿去算 Start 按钮的可用态而不必真的动手
 */
fun interface RunPrecheck {
    suspend fun evaluate(ctx: RunContext): Verdict
}

sealed interface Verdict {
    data object Pass : Verdict

    data class Block(val reason: UiText) : Verdict

    /**
     * 要用户点头才能继续
     *
     * 检查只声明「这事要点头」，谁来点、没人点怎么办由编排层按 [RunContext.trigger] 决定：
     * 放进检查里的话，每加一个检查都得记得降级，忘一次就会在定时触发时弹出没人能点的框
     *
     * [token] 必须在下一轮被本检查自己认出来并跳过，否则用户点一次问一次，死循环
     */
    data class NeedsConfirmation(val token: ConfirmToken, val prompt: UiText) : Verdict
}
