package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunMode

/**
 * 前台模式投递前等控制层面板撤干净
 *
 * 面板盖在主屏上，Runner 一开跑就截首帧，面板还在会被当成目标应用画面。
 * 后台模式截的是虚拟屏，面板盖不着，不挂
 */
class OverlayClearHook(private val awaitCleared: suspend () -> Unit) : RunEnvHook {

    override val id: String = "overlay-clear"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.OVERLAY_CLEAR
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.runMode != RunMode.FOREGROUND) return EngageResult.Skipped()
        awaitCleared()
        return EngageResult.Skipped()
    }
}
