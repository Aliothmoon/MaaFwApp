package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.WatchdogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目标应用在虚拟屏上掉了，把它写进本轮的运行日志
 *
 * 特权进程侧的 `AppWatchdog` 一直在记 `Ln.w`，但那份日志用户看不到；跑飞的那一刻
 * 运行日志里没有任何线索，只剩识别一路超时
 *
 * 两种结局分开报：进程被杀与窗口离屏，用户要做的事完全不同
 *
 * 挂 [Anchor.AfterAccepted]：看门狗是 `startRun` 里跟着虚拟屏一起起来的，
 * 投递之前它还没有目标可盯
 */
class WatchdogNoticeHook(
    private val watchdogState: StateFlow<WatchdogState>,
    private val servicePort: PrivilegedServicePort,
    private val journal: RunJournal,
    private val scope: CoroutineScope,
) : RunEnvHook {

    override val id: String = "watchdog-notice"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.WATCHDOG_NOTICE

    /** 报个信而已，挂不上不该拦住任务 */
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val executionId = ctx.executionId
        val job = scope.launch {
            watchdogState
                // 上一轮留下的坏状态还没被 2s 轮询刷掉，别拿它当本轮的事
                .dropWhile { it.isLost }
                .distinctUntilChanged()
                .filter { it.isLost }
                .collect { state -> journal.note(executionId, RunNote.Warning, describe(state)) }
        }
        return EngageResult.Engaged { job.cancel() }
    }

    private suspend fun describe(state: WatchdogState) = uiTextOf(
        when (state) {
            WatchdogState.APP_DIED -> R.string.run_log_app_process_gone
            else -> R.string.run_log_app_left_display
        },
        targetPackage(),
    )

    /** 包名只有特权进程知道：外壳不维护包名表，`start_app` 的目标来自 pipeline */
    private suspend fun targetPackage() = withContext(MaaDispatchers.IO) {
        runCatching { servicePort.serviceOrNull()?.watchdogTargetPackage() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::uiTextFormatted)
            ?: uiTextOf(R.string.run_log_app_unknown)
    }
}
