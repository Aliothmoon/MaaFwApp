package com.aliothmoon.maafw.runner

/**
 * 执行受理后拉起保活前台服务
 *
 * 只能挂 [Anchor.AfterAccepted]：前台服务 onCreate 时读 RunnerState 判去留，
 * 提前拉会撞上「还没进 Preparing」而当场自停
 *
 * 不返回 [Release]——前台服务自己观察 RunnerState，非 busy 即自停，收尾不必插手。
 * 也不 gating：保活拉不起来只是可能被系统清掉，不该反过来拦住这一轮
 */
class KeepAliveHook(private val keepAlive: RunKeepAlive) : RunEnvHook {

    override val id: String = "keep-alive"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = 0
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        keepAlive.start()
        return EngageResult.Skipped()
    }
}
