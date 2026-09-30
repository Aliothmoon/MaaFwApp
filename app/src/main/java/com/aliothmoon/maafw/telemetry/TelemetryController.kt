package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.TelemetryDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.TaskResult
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * PI v2.9.0 `telemetry.sentry` 的落地
 *
 * DSN 只来自 PI，外壳没有自己的上报去处；用户开关关着、PI 版本是开发态、或 PI 压根没声明
 * 这一段时都不初始化。**只上报可枚举的东西**：事件名、节点名、任务名、选项的 case 名；
 * 自由文本输入只报填没填（[TelemetrySummary]），focus 正文一概不带
 *
 * 一轮运行 = 一条 `run` 事务，每个任务 = 一条 `mxu.task` 子 Span；data / tag 对齐 MXU 的
 * `mxu.task_run`，事务名与 op 保持本外壳原样
 */
class TelemetryController(
    private val context: Context,
    private val projectRepository: ProjectRepository,
    private val settings: AppSettingsManager,
    private val focusDispatcher: FocusDispatcher,
    private val runnerPort: RunnerPort,
    private val scope: CoroutineScope,
) {

    private data class ActiveTelemetry(
        val definition: TelemetryDefinition,
        val appName: String,
        val appVersion: String?,
    )

    /**
     * 一轮的追踪状态
     *
     * [seenBusy] / [ended] 防的是 StateFlow 合并：收集方可能拿着开跑前那个 Idle 迟到，
     * 也可能整轮都没看到忙碌态就直接看到终局 Idle，两个信号任一到了才认 Idle 为本轮终局
     */
    private class RunTrace(
        val executionId: String,
        val runId: String,
        val transaction: ITransaction,
    ) {
        val taskSpans = mutableMapOf<Int, ISpan>()
        var finishedCount = 0
        var seenBusy = false
        var ended = false
    }

    private val lock = Any()
    private var active: ActiveTelemetry? = null
    private var run: RunTrace? = null

    fun setup() {
        scope.launch {
            combine(projectRepository.state, settings.telemetryEnabled) { project, enabled ->
                val definition = (project as? ProjectState.Ready)?.definition
                val telemetry = definition?.telemetry
                when {
                    !enabled -> null
                    definition == null || telemetry == null -> null
                    isDebugProjectVersion(definition.version) -> null
                    else -> ActiveTelemetry(telemetry, definition.name, definition.version)
                }
            }.distinctUntilChanged().collect(::apply)
        }
        scope.launch {
            focusDispatcher.traced.collect { focus ->
                if (active == null) return@collect
                Sentry.captureMessage(focus.message, SentryLevel.INFO)
            }
        }
        scope.launch {
            runnerPort.events.collect { envelope ->
                when (val event = envelope.event) {
                    is RunnerEvent.Progress -> onTaskStarted(envelope.executionId, event)
                    RunnerEvent.ExecutionFinished -> onExecutionFinished(envelope.executionId)
                    else -> Unit
                }
            }
        }
        scope.launch {
            runnerPort.state.collect(::onRunnerState)
        }
    }

    private fun apply(telemetry: ActiveTelemetry?) = synchronized(lock) {
        run = null
        if (telemetry == null) {
            if (active != null) {
                Sentry.close()
                active = null
            }
            return
        }
        // Sentry 换不了 DSN，重来一次要先关；同一份声明重复应用由 distinctUntilChanged 挡在上面
        if (active != null) Sentry.close()
        runCatching { init(telemetry) }
            .onFailure { Timber.w(it, "Failed to init telemetry") }
            .onSuccess { active = telemetry }
    }

    private fun init(telemetry: ActiveTelemetry) {
        val definition = telemetry.definition
        SentryAndroid.init(context) { options ->
            options.dsn = definition.dsn
            options.environment = definition.environment
            options.release = BuildConfig.VERSION_NAME
            options.tracesSampleRate = if (definition.tracing) definition.tracesSampleRate else 0.0
            // 自动采集面全部关掉，只留本类显式发出的那几种事件
            options.isSendDefaultPii = false
            options.isEnableAutoSessionTracking = false
            options.isAnrEnabled = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAutoActivityLifecycleTracing = false
        }
        Sentry.setUser(User().apply { id = TelemetryUserId.get(context) })
        Sentry.setTag("client", CLIENT_NAME)
        Sentry.setTag("app.name", telemetry.appName)
        telemetry.appVersion?.takeIf(String::isNotBlank)?.let { Sentry.setTag("app.version", it) }
    }

    /** 事务开在首个任务真正开跑时，不在 Preparing：准备阶段失败不算一轮，与 MXU 在 post_task 前才开一致 */
    private fun onTaskStarted(executionId: String, progress: RunnerEvent.Progress) = synchronized(lock) {
        val definition = active?.definition ?: return
        if (!definition.tracing) return
        val trace = run?.takeIf { it.executionId == executionId } ?: startRun(executionId)
        trace.taskSpans[progress.completed] = trace.transaction.startChild(TASK_OP, progress.taskName).apply {
            setData("run_id", trace.runId)
            setData("task", progress.taskName)
        }
    }

    private fun onExecutionFinished(executionId: String) = synchronized(lock) {
        val trace = run?.takeIf { it.executionId == executionId } ?: return
        trace.ended = true
        val state = runnerPort.state.value
        if (state.phase == RunnerPhase.Idle) finishRun(trace, state.latestResult)
    }

    private fun onRunnerState(state: RunnerState) = synchronized(lock) {
        val trace = run ?: return
        val execution = state.activeExecution
        if (execution?.executionId == trace.executionId) {
            trace.seenBusy = true
            finishTasks(trace, execution.taskResults)
        }
        if (state.phase == RunnerPhase.Idle && (trace.seenBusy || trace.ended)) {
            finishRun(trace, state.latestResult)
        }
    }

    private fun startRun(executionId: String): RunTrace {
        // 上一轮没等到终局（进程内对账丢了事件），按取消收掉，别让它挂到新一轮上
        run?.let { finishRun(it, null) }

        val execution = runnerPort.state.value.activeExecution?.takeIf { it.executionId == executionId }
        val runId = UUID.randomUUID().toString()
        val taskNames = execution?.taskNames.orEmpty()
        val transaction = Sentry.startTransaction(RUN_NAME, RUN_OP).apply {
            setData("run_id", runId)
            setTag("run.id", runId)
            setData("task_count", execution?.totalTaskCount ?: taskNames.size)
            if (taskNames.isNotEmpty()) setData("tasks", taskNames.joinToString(","))
            execution?.controllerName?.takeIf(String::isNotBlank)?.let {
                setData("controller.name", it)
                setTag("controller.name", it)
            }
            execution?.controllerType?.takeIf(String::isNotBlank)?.let {
                setData("controller.type", it)
                setTag("controller.type", it)
            }
        }
        return RunTrace(executionId, runId, transaction).also { run = it }
    }

    /** 结果按完成顺序追加，第 i 条恰好对应下标 i 开的那个任务 */
    private fun finishTasks(trace: RunTrace, results: List<TaskResult>) {
        for (index in trace.finishedCount until results.size) {
            trace.taskSpans.remove(index)?.let { span ->
                val status = taskSpanStatus(results[index].success)
                span.setData("result", resultLabel(status))
                span.finish(status)
            }
        }
        trace.finishedCount = maxOf(trace.finishedCount, results.size)
    }

    private fun finishRun(trace: RunTrace, result: ExecutionResult?) {
        finishTasks(trace, result?.taskResults.orEmpty())
        trace.taskSpans.values.forEach { span ->
            span.setData("result", resultLabel(SpanStatus.CANCELLED))
            span.finish(SpanStatus.CANCELLED)
        }
        trace.taskSpans.clear()

        val status = runSpanStatus(result)
        trace.transaction.setData("result", resultLabel(status))
        trace.transaction.finish(status)
        if (run === trace) run = null
    }

    private companion object {
        const val CLIENT_NAME = "MaaFwApp"
        const val RUN_NAME = "run"
        const val RUN_OP = "task.run"
        const val TASK_OP = "mxu.task"
    }
}
