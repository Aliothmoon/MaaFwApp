package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf

/**
 * 前台模式不发起执行（对齐 MaaMeow）
 *
 * 从 `SessionViewModel.start()` 搬过来：留在 VM 里只压得住任务页这一个入口，
 * 定时触发不经任务页，那条路绕得过去
 *
 * 将来若改成「前台模式允许跑，但要求用户先摆好环境（如横屏）」，改这一处即可
 */
object ForegroundModePrecheck : RunPrecheck {

    override suspend fun evaluate(ctx: RunContext): Verdict =
        if (ctx.runMode == RunMode.FOREGROUND) {
            Verdict.Block(uiTextOf(R.string.runner_foreground_blocked))
        } else {
            Verdict.Pass
        }
}
