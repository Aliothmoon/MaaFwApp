package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.log.ProcessExitRecorder
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.WatchdogState
import com.aliothmoon.maafw.notification.NotificationCenter
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
 * 两种结局分开报：进程被杀与窗口离屏，用户要做的事完全不同。同一句话另发一条「运行中提醒」通知，
 * 但不中止整轮：PI 的 StopApp、主动重启目标应用都是正常动作，看门狗的目标包又是从屏上反推的，中止容易误杀
 *
 * 挂 [Anchor.AfterAccepted]：看门狗是 `startRun` 里跟着虚拟屏一起起来的，
 * 投递之前它还没有目标可盯
 */
class WatchdogNoticeHook(
    private val watchdogState: StateFlow<WatchdogState>,
    private val servicePort: PrivilegedServicePort,
    private val journal: RunJournal,
    private val scope: CoroutineScope,
    /** 进程被杀时把系统记下的死因写进应用日志；运行日志里只留一句提示 */
    private val exitRecorder: ProcessExitRecorder,
    private val notifications: NotificationCenter,
) : RunEnvHook {

    override val id: String = "watchdog-notice"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.WATCHDOG_NOTICE

    /** 报个信而已，挂不上不该拦住任务 */
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val job = scope.launch {
            watchdogState
                // 上一轮留下的坏状态还没被 2s 轮询刷掉，别拿它当本轮的事
                .dropWhile { it.isLost }
                .distinctUntilChanged()
                .filter { it.isLost }
                .collect { state ->
                    val packageName = targetPackage()
                    val notice = describe(state, packageName)
                    journal.note(ctx.executionId, RunNote.Warning, notice)
                    notifications.onRunAlert(notice)
                    if (state == WatchdogState.APP_DIED && packageName != null) exitRecorder.recordTarget(packageName)
                }
        }
        return EngageResult.Engaged { job.cancel() }
    }

    private fun describe(state: WatchdogState, packageName: String?) = uiTextOf(
        when (state) {
            WatchdogState.APP_DIED -> R.string.run_log_app_process_gone
            else -> R.string.run_log_app_left_display
        },
        packageName?.let(::uiTextFormatted) ?: uiTextOf(R.string.run_log_app_unknown),
    )

    /** 包名只有特权进程知道：外壳不维护包名表，`start_app` 的目标来自 pipeline */
    private suspend fun targetPackage(): String? = withContext(MaaDispatchers.IO) {
        runCatching { servicePort.serviceOrNull()?.watchdogTargetPackage() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
