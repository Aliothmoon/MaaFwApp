package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** 读盘遇到非数字或越界一律回落默认值；写入则钳进区间，输入框失焦时已先钳过一次 */
object RunDurationLimit {
    const val DEFAULT_MINUTES = 240
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440

    fun normalize(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun parse(raw: String): Int =
        raw.toIntOrNull()?.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: DEFAULT_MINUTES
}

/**
 * 单轮运行的最长执行时间
 *
 * 挂 [Anchor.AfterAccepted]：只计真正受理的那一轮，准备阶段的失败不该消耗它。
 * 分钟数在 engage 时捕获进闭包，运行中改设置只影响下一轮
 *
 * 到点停下的结局仍是 [ExecutionResult.Cancelled]，与手动停一样不播报；
 * 只借 [RunContext.stoppedAtLimit] 让「跑完关应用」照常执行
 */
class RunDurationLimitHook(
    private val settings: AppSettingsGateway,
    private val runnerPort: RunnerPort,
    private val scope: CoroutineScope,
) : RunEnvHook {

    override val id: String = "run-duration-limit"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.RUN_DURATION_LIMIT
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (!settings.runDurationLimitEnabled.value) return EngageResult.Skipped()

        val minutes = RunDurationLimit.normalize(settings.runDurationLimitMinutes.value)
        val job = scope.launch {
            delay(minutes.minutes)
            // 本轮恰好自然跑完、release 还没轮到时，下一轮可能已经受理；stop 不带轮次，停下去就停错了
            if (runnerPort.state.value.activeExecution?.executionId != ctx.executionId) return@launch

            // 先写再停：停下之后会话文件随时会关，晚一步这行就落不进本轮
            ctx.journal.warn(ctx.executionId, uiTextOf(R.string.run_log_duration_limit_reached, minutes))
            // 标记要早于 stop：release 先撤这个计时器，stop 挂起期间本协程就可能被取消
            ctx.stoppedAtLimit.set(true)
            when (val result = runnerPort.stop()) {
                RunnerCommandResult.Accepted -> Unit
                is RunnerCommandResult.Rejected -> {
                    ctx.stoppedAtLimit.set(false)
                    ctx.journal.error(
                        ctx.executionId,
                        uiTextOf(R.string.run_log_duration_limit_stop_failed, result.reason),
                    )
                }
            }
        }
        return EngageResult.Engaged { job.cancel() }
    }
}
