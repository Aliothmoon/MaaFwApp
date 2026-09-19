package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/** 运行时长上限的边界；历史遗留或手改的非法值也收敛到这里 */
object RunDurationLimit {
    const val DEFAULT_MINUTES = 240
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440

    fun normalize(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}

/**
 * 单轮运行的最长执行时间
 *
 * 放在 `AfterAccepted`：上限只管真正受理的那一轮，准备阶段的失败不该消耗它。
 * 值在 engage 时冻结，运行中改设置只影响下一轮——和环境开关的语义保持一致
 */
class RunDurationLimitHook(
    private val settings: AppSettingsGateway,
    private val runnerPort: RunnerPort,
    private val journal: RunJournal,
    private val scope: CoroutineScope,
) : RunEnvHook {

    override val id: String = "run-duration-limit"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = 60
    override val gating: Boolean = false

    /** 正常结束与到点停止都会经过 release；只留一个计时协程，防止同一实例跨轮残留 */
    private val timer = AtomicReference<Job?>(null)

    override suspend fun engage(ctx: RunContext): EngageResult {
        if (!settings.runDurationLimitEnabled.value) return EngageResult.Skipped()

        val minutes = RunDurationLimit.normalize(settings.runDurationLimitMinutes.value)
        val job = scope.launch {
            delay(minutes.minutes)
            journal.warn(uiTextOf(R.string.run_log_duration_limit_reached, minutes))
            try {
                runnerPort.stop()
            } catch (t: Throwable) {
                Timber.w(t, "failed to stop the run at its duration limit")
            }
        }
        timer.getAndSet(job)?.cancel()
        return EngageResult.Engaged {
            if (timer.compareAndSet(job, null)) job.cancel()
        }
    }
}
