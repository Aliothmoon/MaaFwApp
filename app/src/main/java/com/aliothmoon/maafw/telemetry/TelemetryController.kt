package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.TelemetryDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * PI v2.9.0 `telemetry.sentry` 的落地，事件模型与字段对齐 MXU `commands/telemetry.rs`
 *
 * DSN 只来自 PI，外壳没有自己的上报去处；用户开关关着、PI 版本是开发态、或 PI 压根没声明
 * 这一段时都不初始化。开关缺省为开，与 MXU `helpImproveSoftware ?? true` 一致
 *
 * 上报面：哈希后的设备 ID、硬件摘要、版本、任务名、脱敏后的选项（[TelemetrySummary]）、
 * 任务与节点的结果（[RunTracer]）；focus 正文、截图、日志正文一概不带
 */
class TelemetryController(
    private val context: Context,
    private val projectRepository: ProjectRepository,
    private val settings: AppSettingsManager,
    private val runnerPort: RunnerPort,
    private val scope: CoroutineScope,
) {

    private data class ActiveTelemetry(
        val definition: TelemetryDefinition,
        val appName: String,
        val appVersion: String,
    )

    private val lock = Any()
    private var active: ActiveTelemetry? = null
    private val tracer = RunTracer(startTransaction = { name, op -> Sentry.startTransaction(name, op) })

    fun setup() {
        scope.launch {
            // 开关关着也记：用户反馈问题时可凭这行在 Sentry 后台按 user.id 定位
            Timber.i("[telemetry] 匿名设备 ID (Sentry user.id) = %s", TelemetryUserId.get(context))
        }
        scope.launch {
            combine(projectRepository.state, settings.telemetryEnabled) { project, enabled ->
                val definition = (project as? ProjectState.Ready)?.definition
                val telemetry = definition?.telemetry
                when {
                    !enabled -> null
                    definition == null || telemetry == null -> null
                    isDebugProjectVersion(definition.version) -> null
                    else -> ActiveTelemetry(telemetry, definition.name, definition.version ?: DEFAULT_APP_VERSION)
                }
            }.distinctUntilChanged().collect(::apply)
        }
        scope.launch {
            runnerPort.events.collect { envelope ->
                val executionId = envelope.executionId
                when (val event = envelope.event) {
                    RunnerEvent.ExecutionFinished -> {
                        val result = awaitResult(executionId)
                        synchronized(lock) { tracer.onExecutionFinished(executionId, result) }
                    }

                    else -> synchronized(lock) {
                        if (active?.definition?.tracing == true) tracer.onEvent(executionId, event) { planOf(executionId) }
                    }
                }
            }
        }
    }

    /**
     * 终局 marker 先于 phase 收回 Idle 发出，结局要等 state 翻过这一轮再取；
     * 等到的若已是下一轮（activeExecution 非空），这一轮的结局就被盖掉了，返回 null
     */
    private suspend fun awaitResult(executionId: String): ExecutionResult? =
        withTimeoutOrNull(RESULT_WAIT_MS) {
            runnerPort.state.first { it.activeExecution?.executionId != executionId }
        }?.takeIf { it.activeExecution == null }?.latestResult

    private fun planOf(executionId: String): RunPlan? =
        runnerPort.state.value.activeExecution?.takeIf { it.executionId == executionId }?.plan

    private fun apply(telemetry: ActiveTelemetry?) {
        synchronized(lock) {
            tracer.reset()
            if (active != null) {
                // 先正常结束 Session，否则它会被判为 abnormal，拉低 crash-free 率
                Sentry.endSession()
                Sentry.close()
                active = null
            }
            // Sentry 换不了 DSN，重来一次要先关；同一份声明重复应用由 distinctUntilChanged 挡在上面
            if (telemetry == null) return
            runCatching { init(telemetry) }
                .onFailure { Timber.w(it, "Failed to init telemetry") }
                .onSuccess { active = telemetry }
        }
    }

    private fun init(telemetry: ActiveTelemetry) {
        val definition = telemetry.definition
        SentryAndroid.init(context) { options ->
            options.dsn = definition.dsn
            options.environment = definition.environment
            // 与 MXU 的 `MXU@<mxuVersion>+<appName>@<appVersion>` 同形
            options.release = "$CLIENT_NAME@${BuildConfig.VERSION_NAME}+${telemetry.appName}@${telemetry.appVersion}"
            options.tracesSampleRate = if (definition.tracing) definition.tracesSampleRate.coerceIn(0.0, 1.0) else 0.0
            options.isSendDefaultPii = false
            // Session（Release Health）与 MXU 一样开着，日活与 crash-free 率靠它
            options.isEnableAutoSessionTracking = true
            // 其余自动采集面全部关掉，只留本类显式发出的事件
            options.isAnrEnabled = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAutoActivityLifecycleTracing = false
        }
        Sentry.setUser(User().apply { id = TelemetryUserId.get(context) })
        Sentry.setTag("app.name", telemetry.appName)
        Sentry.setTag("app.version", telemetry.appVersion)
        Sentry.setTag("maafwapp.version", BuildConfig.VERSION_NAME)
        val hardware = TelemetryHardware.collect(context)
        Sentry.configureScope { it.setContexts("hardware", hardware) }
    }

    private companion object {
        const val CLIENT_NAME = "MaaFwApp"

        /** 与 MXU 缺省 `interface.version` 时的取值一致 */
        const val DEFAULT_APP_VERSION = "0.0.0"

        /** onFinished 里发 marker 与写 Idle 是同一线程前后脚，等这么久只防意外 */
        const val RESULT_WAIT_MS = 2_000L
    }
}
