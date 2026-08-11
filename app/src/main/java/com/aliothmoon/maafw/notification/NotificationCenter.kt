package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.i18n.uiTextPlural
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunEndReason
import com.aliothmoon.maafw.runner.RunLogRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「什么时候该发什么通知」的唯一落点：系统通知与外部推送在这里汇合
 *
 * 两条投递面各管各的开关——系统通知看 `AppSettings.eventNotificationLevel`，
 * 外部推送看 [NotificationSettings] 的三个触发开关。合并成一处是为了让「一轮结束
 * 到底算什么结局」只判一次，两边的标题不会各说各的
 */
class NotificationCenter(
    private val eventNotifier: RunEventNotifier,
    private val external: ExternalNotificationService,
    private val settings: NotificationSettingsManager,
    private val recorder: RunLogRecorder,
    /** [UiText] → 成品文本；推送发出去的是字符串，资源 id 到不了对面 */
    private val renderText: (UiText) -> String,
    private val servicePort: PrivilegedServicePort,
) {

    /**
     * 整轮收尾时判一次结局
     *
     * 一轮**只播报一条**：内部通知共用同一个 notify id，两条会互相顶掉；外部推送发两条则是纯吵。
     * 有失败的那一轮同时命中「完成」与「出错」两个开关，任一开着就发——只认其中一个的话，
     * 只开了「完成」的用户在任务挂掉那轮反而收不到任何东西
     */
    suspend fun onRunFinished(reason: RunEndReason) {
        // 没投出去 / 用户自己按的停：都不是要播报的结局
        val result = (reason as? RunEndReason.Ran)?.result ?: return
        if (result is ExecutionResult.Cancelled) return

        val outcome = describe(result)
        val title = renderText(outcome.title)
        val body = renderText(outcome.body)
        eventNotifier.notifyRunFinished(title, body, outcome.isError)

        val current = settings.current()
        val wanted = when (outcome.trigger) {
            Trigger.COMPLETE -> current.sendOnComplete.toPrefBoolean(default = true)
            Trigger.SERVICE_DIED -> current.sendOnServiceDied.toPrefBoolean()
            Trigger.ERROR ->
                current.sendOnError.toPrefBoolean(default = true) ||
                    current.sendOnComplete.toPrefBoolean(default = true)
        }
        if (!wanted) return

        external.send(title, appendLogs(body, current))
    }

    /**
     * 特权进程死掉这一档单独判，不与普通失败混在一起
     *
     * `MaaFrameworkRunnerPort` 自己就在监听 `Died` 并中止本轮，所以这里不必再挂一个观察者——
     * 那样同一件事会报两遍（一条「服务中断」、一条「执行失败」）。收尾这一刻现查连接态即可：
     * 死了才走这一档，中途又连回来的按普通失败报，反正失败原因本身也会说清是谁退了
     */
    private fun describe(result: ExecutionResult): Outcome {
        if (result is ExecutionResult.Failed &&
            servicePort.serviceState.value == PrivilegedServiceState.Died
        ) {
            return Outcome(
                title = uiTextOf(R.string.notification_event_service_died),
                body = uiTextOf(R.string.notification_event_service_died_body),
                isError = true,
                trigger = Trigger.SERVICE_DIED,
            )
        }

        return when (result) {
            is ExecutionResult.Completed -> Outcome(
                title = uiTextOf(R.string.notification_event_run_completed),
                body = uiTextPlural(
                    R.plurals.notification_event_run_completed_body,
                    result.taskResults.size,
                    result.taskResults.size,
                ),
                isError = false,
                trigger = Trigger.COMPLETE,
            )

            is ExecutionResult.CompletedWithFailures -> {
                val failed = result.taskResults.filterNot { it.success }.map { it.taskName }
                Outcome(
                    title = uiTextOf(R.string.notification_event_run_partial),
                    body = uiTextOf(
                        R.string.notification_event_run_partial_body,
                        result.taskResults.size - failed.size,
                        result.taskResults.size,
                        failed.joinToString("、"),
                    ),
                    isError = true,
                    trigger = Trigger.ERROR,
                )
            }

            is ExecutionResult.Failed -> Outcome(
                title = uiTextOf(R.string.notification_event_run_failed),
                body = result.reason,
                isError = true,
                trigger = Trigger.ERROR,
            )

            // 调用方已经挡掉，这里只为让 when 穷尽
            is ExecutionResult.Cancelled -> error("cancelled runs are not reported")
        }
    }

    /**
     * 把内存里那份运行日志附在正文后面
     *
     * 取的是 [RunLogRecorder] 的内存快照而不是会话文件：收尾这一刻文件还没冲完，
     * 读它会缺最后几行——而挂掉前的最后几行恰恰是排障时最想看的
     */
    private fun appendLogs(body: String, current: NotificationSettings): String {
        if (!current.includeLogDetails.toPrefBoolean()) return body
        val logs = recorder.runLog.value
            .takeLast(LOG_TAIL_LINES)
            .joinToString("\n") { "[${TIME_OF_DAY.format(Date(it.atMillis))}] ${renderText(it.text)}" }
        return if (logs.isEmpty()) body else "$body\n\n$logs"
    }

    /** 命中哪个开关；与 [Outcome.isError] 分开，后者只管配色 */
    private enum class Trigger { COMPLETE, ERROR, SERVICE_DIED }

    private data class Outcome(
        val title: UiText,
        val body: UiText,
        val isError: Boolean,
        val trigger: Trigger,
    )

    private companion object {
        /** 内存里最多留 500 条，整份塞进推送会被多数渠道截断甚至拒收 */
        const val LOG_TAIL_LINES = 80

        /** 与日志页同款，固定 locale：用户要拿它去对 logcat */
        val TIME_OF_DAY = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
