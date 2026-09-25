package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.TelemetryDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * PI v2.9.0 `telemetry.sentry` 的落地
 *
 * DSN 只来自 PI，外壳没有自己的上报去处；用户开关关着、PI 版本是开发态、或 PI 压根没声明
 * 这一段时都不初始化。**只上报可枚举的东西**：事件名、节点名、任务名、选项的 case 名；
 * 自由文本输入只报填没填（[TelemetrySummary]），focus 正文一概不带
 */
class TelemetryController(
    private val context: Context,
    private val projectRepository: ProjectRepository,
    private val settings: AppSettingsManager,
    private val focusDispatcher: FocusDispatcher,
    private val runnerPort: RunnerPort,
    private val scope: CoroutineScope,
) {

    private var active: TelemetryDefinition? = null
    private var runTransaction: ITransaction? = null

    fun setup() {
        scope.launch {
            combine(projectRepository.state, settings.telemetryEnabled) { project, enabled ->
                val definition = (project as? ProjectState.Ready)?.definition
                when {
                    !enabled -> null
                    definition == null -> null
                    isDebugProjectVersion(definition.version) -> null
                    else -> definition.telemetry
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
            runnerPort.state.collect { state ->
                val definition = active ?: return@collect
                if (!definition.tracing) return@collect
                when (state.phase) {
                    RunnerPhase.Preparing -> startRunTransaction(state.activeExecution?.totalTaskCount)
                    RunnerPhase.Idle -> finishRunTransaction(state.latestResult)
                    else -> Unit
                }
            }
        }
    }

    private fun apply(definition: TelemetryDefinition?) {
        if (definition == null) {
            if (active != null) {
                Sentry.close()
                active = null
            }
            return
        }
        // Sentry 换不了 DSN，重来一次要先关；同一份声明重复应用由 distinctUntilChanged 挡在上面
        if (active != null) Sentry.close()
        runCatching { init(definition) }
            .onFailure { Timber.w(it, "Failed to init telemetry") }
            .onSuccess { active = definition }
    }

    private fun init(definition: TelemetryDefinition) {
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
    }

    private fun startRunTransaction(taskCount: Int?) {
        if (runTransaction != null) return
        runTransaction = Sentry.startTransaction("run", "task.run").apply {
            taskCount?.let { setData("task_count", it) }
        }
    }

    private fun finishRunTransaction(result: ExecutionResult?) {
        val transaction = runTransaction ?: return
        runTransaction = null
        transaction.finish(
            when (result) {
                is ExecutionResult.Completed -> SpanStatus.OK
                is ExecutionResult.CompletedWithFailures -> SpanStatus.UNKNOWN_ERROR
                is ExecutionResult.Cancelled -> SpanStatus.CANCELLED
                is ExecutionResult.Failed -> SpanStatus.INTERNAL_ERROR
                null -> SpanStatus.OK
            },
        )
    }
}
