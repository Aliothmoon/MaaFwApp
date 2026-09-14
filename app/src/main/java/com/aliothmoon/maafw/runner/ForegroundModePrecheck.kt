package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf

/**
 * 前台模式不跑定时触发的那一轮：没人看着的执行不该强占主屏
 *
 * 只拦 [RunTrigger.Schedule]——悬浮窗手动开跑是前台模式的正路（人就在屏幕前），
 * 应用内入口的拦截在 `SessionViewModel.start(surface)`，那里才看得到 surface
 */
object ForegroundModePrecheck : RunPrecheck {

    override suspend fun evaluate(ctx: RunContext): Verdict =
        if (ctx.runMode == RunMode.FOREGROUND && ctx.trigger is RunTrigger.Schedule) {
            Verdict.Block(uiTextOf(R.string.runner_foreground_blocked))
        } else {
            Verdict.Pass
        }
}
