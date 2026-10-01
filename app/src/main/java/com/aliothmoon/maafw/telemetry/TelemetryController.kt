package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.TelemetryDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * PI v2.9.0 `telemetry.sentry` 的落地，事件模型与字段对齐 MXU `commands/telemetry.rs`
 *
 * DSN 只来自 PI，外壳没有自己的上报去处；用户开关关着或 PI 压根没声明这一段时不初始化。
 * 开关缺省为开，与 MXU `helpImproveSoftware ?? true` 一致；debug 构建同样上报，靠 `maafwapp.build_type` 区分
 *
 * 上报面：哈希后的设备 ID、硬件摘要、版本、任务名、脱敏后的选项（[TelemetrySummary]）、
 * 任务与节点的结果（[RunTracer]）、任务终态失败的事件（[TaskFailure]）；focus 正文、截图、日志正文一概不带
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
        val environment: String,
    )

    private val lock = Any()
    private var active: ActiveTelemetry? = null
    private val tracer = RunTracer(
        startTransaction = { name, op -> Sentry.startTransaction(name, op) },
        onTaskFailure = ::captureFailure,
    )

    /** 取值不随 DSN 变，重新初始化不必再查一遍 ActivityManager */
    private val hardware by lazy { TelemetryHardware.collect(context) }

    fun setup() {
        scope.launch {
            // 开关关着也记：用户反馈问题时可凭这行在 Sentry 后台按 user.id 定位
            Timber.i("[telemetry] 匿名设备 ID (Sentry user.id) = %s", TelemetryUserId.get(context))
        }
        scope.launch {
            combine(
                projectRepository.state,
                settings.loaded,
                settings.telemetryEnabled,
                settings.updateChannel,
            ) { project, loaded, enabled, channel ->
                val definition = (project as? ProjectState.Ready)?.definition
                val telemetry = definition?.telemetry
                when {
                    // 读盘前开关与频道都还是默认值，照着初始化会绕过用户的关闭，environment 也会记成默认频道
                    !loaded || !enabled -> null
                    definition == null || telemetry == null -> null
                    else -> ActiveTelemetry(
                        definition = telemetry,
                        appName = definition.name,
                        appVersion = telemetryAppVersion(definition.version, BuildConfig.MAFW_PROJECT_VERSION),
                        // PI 没写就用更新频道，与 MXU 一致：桌面端按 stable / beta 分的看板才看得到这边
                        environment = telemetry.environment ?: channel.name.lowercase(),
                    )
                }
            }.distinctUntilChanged().collect(::apply)
        }
        scope.launch {
            runnerPort.events.collect { envelope ->
                synchronized(lock) {
                    // 不看 tracing：它关着时采样率是 0，事务不发，失败事件照发，与 MXU 一致
                    if (active != null) tracer.onEvent(envelope.executionId, envelope.event)
                }
            }
        }
    }

    /** 由 [tracer] 在上面那把锁里回调，[active] 此刻就是这轮所属的那份 */
    private fun captureFailure(failure: TaskFailure) {
        val appName = active?.appName ?: return
        val eventId = Sentry.captureEvent(failure.toSentryEvent(appName))
        // 与设备 ID 那行同理：凭它能从用户的日志直接找到 Sentry 里的这条事件
        Timber.i("[telemetry] task failure event_id=%s run_id=%s task=%s", eventId, failure.runId, failure.task)
    }

    /** 由 [TelemetryHook] 在投递前调用 */
    fun begin(executionId: String, plan: RunPlan) = synchronized(lock) { tracer.begin(executionId, plan) }

    fun forget(executionId: String) = synchronized(lock) { tracer.forget(executionId) }

    /**
     * 锁只护 [active] 与追踪状态的切换；Sentry 的收尾与初始化（flush、读设备 ID）放在锁外，
     * 不让事件收集方跟着等。本方法只由上面那条 collect 串行调用，不会并发
     */
    private fun apply(telemetry: ActiveTelemetry?) {
        val previous = synchronized(lock) {
            tracer.reset()
            active.also { active = null }
        }
        if (previous != null) {
            // 先正常结束 Session，否则它会被判为 abnormal，拉低 crash-free 率
            Sentry.endSession()
            Sentry.close()
        }
        // Sentry 换不了 DSN，重来一次要先关；同一份声明重复应用由 distinctUntilChanged 挡在上面
        if (telemetry == null) return
        runCatching { init(telemetry) }
            .onFailure { Timber.w(it, "Failed to init telemetry") }
            .onSuccess { synchronized(lock) { active = telemetry } }
    }

    private fun init(telemetry: ActiveTelemetry) {
        val definition = telemetry.definition
        SentryAndroid.init(context) { options ->
            options.dsn = definition.dsn
            options.environment = telemetry.environment
            // 与 MXU 的 `MXU@<mxuVersion>+<appName>@<appVersion>` 同形。前半截是外壳自己的版本：
            // 整包构建时 VERSION_NAME 跟的是外层项目，写它就成了项目版本报两遍
            options.release = "$CLIENT_NAME@${BuildConfig.MAFW_APP_VERSION}+${telemetry.appName}@${telemetry.appVersion}"
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
        Sentry.setTag("maafwapp.version", BuildConfig.MAFW_APP_VERSION)
        Sentry.setTag("maafwapp.build_type", BuildConfig.BUILD_TYPE)
        Sentry.configureScope { it.setContexts("hardware", hardware) }
    }

    private companion object {
        const val CLIENT_NAME = "MaaFwApp"
    }
}

/**
 * 上报用的项目版本：整包构建时取构建期定下的 [buildVersion]。PI 里的 `version` 在源码树里只是占位，
 * 桌面包靠 CI 改写成 tag，APK 没有这一步；没有外层项目可取版本时才用 PI 自己写的
 *
 * PI 的写法带 `v` 就补上：桌面端报的是 `v2.30.1` 这样的原样 tag，前缀对不齐就没法按版本对照。
 * 没有 tag 的仓库版本名退化成短哈希，那不是版本号，原样用
 */
internal fun telemetryAppVersion(piVersion: String?, buildVersion: String): String = when {
    buildVersion.isBlank() -> piVersion?.takeIf(String::isNotBlank) ?: DEFAULT_APP_VERSION
    piVersion?.startsWith("v", ignoreCase = true) == true && NUMERIC_VERSION.containsMatchIn(buildVersion) -> "v$buildVersion"
    else -> buildVersion
}

private val NUMERIC_VERSION = Regex("""^\d+\.\d+""")

/** 与 MXU 缺省 `interface.version` 时的取值一致 */
private const val DEFAULT_APP_VERSION = "0.0.0"
