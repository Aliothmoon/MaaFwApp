package com.aliothmoon.maafw.runner

/**
 * 整轮的会话日志文件：engage 开，收尾写 Footer 并关
 *
 * 借 [RunEnvHook] 的形状而不是让 `RunLauncher` 直接调录制器——`engage/Release(reason)`
 * 正好是「开文件 / 按结局收尾」要的一对，且保活（`KeepAliveHook`）已经是同样路数
 *
 * 挂在 [Anchor.BeforeDispatch]：`RunnerPort.start` 内部就把 setup、虚拟屏这些都做完了才返回，
 * 挂到 AfterAccepted 会把整个准备阶段的日志漏在文件外面——而那段恰恰是启动失败时唯一的现场
 *
 * 不 gating：日志文件建不出来只是这轮没有历史记录，不该反过来拦住任务
 */
class SessionLogHook(private val journal: RunJournal) : RunEnvHook {

    override val id: String = "session-log"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.SESSION_LOG
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        journal.begin(ctx.plan)
        return EngageResult.Engaged { reason -> journal.end(reason) }
    }
}
