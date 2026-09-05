package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunMode

/** 后台运行期的观察者：只采样与写日志，失败不参与运行成败判定 */
class GameFpsHook(private val watcher: GameFpsWatcher) : RunEnvHook {

    override val id: String = "game-fps"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.GAME_FPS
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (ctx.runMode != RunMode.BACKGROUND) return EngageResult.Skipped()
        watcher.start()
        return EngageResult.Engaged(Release { watcher.stop() })
    }
}
