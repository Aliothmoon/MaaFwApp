package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.runner.Anchor
import com.aliothmoon.maafw.runner.EngageResult
import com.aliothmoon.maafw.runner.HookOrder
import com.aliothmoon.maafw.runner.RunContext
import com.aliothmoon.maafw.runner.RunEnvHook

/**
 * 把本轮冻住的计划交给遥测：任务 Span 的选项摘要、事务上的任务清单与 controller 都从这里来
 *
 * 挂 [Anchor.BeforeDispatch]：首个任务的事件在 `RunnerPort.start` 返回前就可能到，受理后再交就晚了。
 * 不 gating，遥测关着也照登记——一张表项，事件流里用不上就在收尾时丢掉
 */
class TelemetryHook(private val controller: TelemetryController) : RunEnvHook {

    override val id: String = "telemetry"
    override val anchor: Anchor = Anchor.BeforeDispatch
    override val order: Int = HookOrder.TELEMETRY
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        controller.begin(ctx.executionId, ctx.plan)
        return EngageResult.Engaged { controller.forget(ctx.executionId) }
    }
}
