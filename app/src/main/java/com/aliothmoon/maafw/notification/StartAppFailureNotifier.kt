package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.runner.RunLogComposer
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RunnerPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * StartApp 失败不用等整轮结束才播报
 *
 * 复用任务结果通知的同一个 notify id：运行中先看到「应用未启动」，
 * 收尾后由最终结果顶掉，避免同一轮在通知栏里叠两条
 */
class StartAppFailureNotifier(
    private val runnerPort: RunnerPort,
    private val notifyStartAppFailed: (String?) -> Unit,
    private val scope: CoroutineScope,
) {
    private var notifiedExecutionId: String? = null

    fun setup() {
        scope.launch {
            runnerPort.events.collect(::onEvent)
        }
    }

    private fun onEvent(event: RunnerEvent) {
        if (event !is RunnerEvent.Callback) return
        val execution = runnerPort.state.value.activeExecution ?: return
        if (notifiedExecutionId == execution.executionId) return
        if (!RunLogComposer.isStartAppFailure(event)) return

        notifiedExecutionId = execution.executionId
        val taskLabel = RunLogComposer.startAppFailureTaskLabel(
            event,
            execution.currentTaskLabel,
        )
        notifyStartAppFailed(taskLabel)
    }
}
