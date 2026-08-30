package com.aliothmoon.maafw.runner

import android.os.Process
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.LogcatServiceManager
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * RunnerPort 的真实实现：本对象跑在 app 进程，MaaFramework 实例在特权进程里，两者经 binder 通信
 * 这里不持有任何 native handle，也不理解 C API 调用序列（docs/privileged-runtime.md §6）
 */
class MaaFrameworkRunnerPort(
    private val installer: PiInstaller,
    /** 本包 APK 的绝对路径；特权进程从中读 agent 运行时的 assets */
    private val apkPath: String,
    /** 已解压的 native 库目录；agent child 靠它 dlopen libMaaAgentServer.so 及其依赖 */
    private val nativeLibraryDir: String,
    /** 每轮开始时现读，不缓存：用户可能在两轮之间改了运行模式 */
    private val runMode: () -> RunMode,
    /** 同上：分辨率偏好可能两轮之间被改 */
    private val resolutionPreference: () -> ResolutionPreference,
    /** 调试模式：传给特权进程 setup 的 isDebug，开启 MaaFramework 详细日志 */
    private val debugMode: () -> Boolean,
    private val scope: CoroutineScope,
    private val servicePort: PrivilegedServicePort,
) : RunnerPort {

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RunnerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RunnerEvent> = _events.asSharedFlow()

    init {
        // 特权进程死了 onFinished 就永远不会来，phase 卡在 Running，configurationLocked
        // 跟着卡死，UI 全灰且再也发不起下一轮。只认 Died：主动 unbind 只是断了 app 这侧的
        // binder，远端仍握着我们的 callback stub，那一轮还在跑、结果照样会回来
        scope.launch {
            servicePort.serviceState.collect { serviceState ->
                if (serviceState == PrivilegedServiceState.Died) {
                    abortRun(uiTextOf(R.string.msg_fail_privileged_exited), "privileged process died mid-run, forcing abort")
                }
            }
        }
        scope.launch { reconcileWhileRunning() }
    }

    /**
     * 定期反问特权进程「还在跑吗」，答否就收回执行态
     *
     * 状态全靠回调推进，`onFinished` 丢一次 phase 就永久卡在 Running。死亡通知只兜得住
     * 进程没了这一种丢法，binder 拥塞丢包、远端异常没走到 finally 都兜不住，这里是通用的
     * 那条兜底（桌面端 MXU 干脆全程只认 `MaaTaskerRunning` 的现查结果）
     *
     * Preparing 时 startRun 还没发出去，那边本来就没在跑，不问。
     * Stopping 也要问：PostStop 之后 onFinished 丢了，phase 会永远停在 Stopping
     */
    private suspend fun reconcileWhileRunning() {
        _state.map { it.phase }.distinctUntilChanged().collectLatest { phase ->
            if (phase != RunnerPhase.Running && phase != RunnerPhase.Stopping) return@collectLatest
            while (true) {
                delay(RECONCILE_INTERVAL_MS)
                if (remoteRunning() != false) continue
                delay(RECONCILE_GRACE_MS)
                // onFinished 在宽限期内落地了，一切正常
                if (!_state.value.phase.isBusy) return@collectLatest
                if (remoteRunning() != false) continue
                abortRun(uiTextOf(R.string.msg_fail_privileged_reported_finished), "reconciliation found run already finished but result missing, forcing abort")
                return@collectLatest
            }
        }
    }

    /** null = 问不出来（没连上或调用失败），不能据此判定，只有明确的 false 才算数 */
    private suspend fun remoteRunning(): Boolean? = withContext(MaaDispatchers.IO) {
        runCatching { servicePort.serviceOrNull()?.isRunning() }.getOrNull()
    }

    /**
     * 用 getAndUpdate 原子判并换：真正的 onFinished 可能刚好抢在前头落地，
     * 那一份结果比这里编的准，不能覆盖
     */
    private fun abortRun(resultReason: UiText, logMessage: String) {
        val previous = _state.getAndUpdate { current ->
            if (!current.phase.isBusy) {
                current
            } else {
                RunnerState(
                    phase = RunnerPhase.Idle,
                    latestResult = ExecutionResult.Failed(
                        resultReason,
                        current.activeExecution?.taskResults.orEmpty(),
                    ),
                )
            }
        }
        if (previous.phase.isBusy) Timber.w(logMessage)
    }

    /**
     * JVM 单测里 [IMaaRunnerCallback.Stub] 会调未 mock 的 Binder.attachInterface
     * 测 start/stop/对账时换成空操作，避免为测 phase 去构造 AIDL Stub
     */
    internal var bindRunnerCallback: (RemoteService) -> Unit = { service ->
        service.setRunnerCallback(callback)
    }

    private val callback by lazy { object : IMaaRunnerCallback.Stub() {
        override fun onEvent(message: String?, detailsJson: String?) {
            _events.tryEmit(toRunnerEvent(message.orEmpty(), detailsJson.orEmpty()))
        }

        override fun onModalFocus(
            executionId: String?,
            focusId: String?,
            message: String?,
            detailsJson: String?,
        ) {
            _events.tryEmit(
                modalFocusEvent(executionId, focusId, message.orEmpty(), detailsJson.orEmpty()),
            )
        }

        override fun onAgentOutput(line: String?, fromStderr: Boolean) {
            _events.tryEmit(RunnerEvent.AgentOutput(line.orEmpty(), fromStderr))
        }

        override fun onAgentConnected(index: Int, total: Int, exec: String?) {
            _events.tryEmit(RunnerEvent.AgentConnected(index, total, exec.orEmpty()))
        }

        // 不碰 completedTaskCount：那是 onTaskFinished 的账，两边各记一套会在丢事件时永久漂
        override fun onTaskStarted(taskName: String?, index: Int, total: Int) {
            val name = taskName.orEmpty()
            _state.update { current ->
                current.copy(
                    activeExecution = current.activeExecution?.copy(
                        currentTaskName = name,
                        totalTaskCount = total,
                    ),
                )
            }
            _events.tryEmit(RunnerEvent.Progress(name, index, total))
        }

        override fun onTaskFinished(taskName: String?, success: Boolean, message: String?) {
            val result = TaskResult(taskName.orEmpty(), success, message)
            _state.update { current ->
                val execution = current.activeExecution ?: return@update current
                val results = execution.taskResults + result
                current.copy(
                    activeExecution = execution.copy(
                        completedTaskCount = results.size,
                        taskResults = results,
                    ),
                )
            }
        }

        override fun onFinished(outcome: Int, reason: String?) {
            val results = _state.value.activeExecution?.taskResults.orEmpty()
            val result = when (outcome) {
                RunOutcome.COMPLETED -> ExecutionResult.Completed(results)
                RunOutcome.COMPLETED_WITH_FAILURES -> ExecutionResult.CompletedWithFailures(results)
                RunOutcome.CANCELLED -> ExecutionResult.Cancelled(results)
                else -> ExecutionResult.Failed(if (reason.isNullOrBlank()) uiTextOf(R.string.msg_fail_default) else uiTextFromFramework(reason), results)
            }
            _state.value = RunnerState(phase = RunnerPhase.Idle, latestResult = result)
        }
    } }

    override suspend fun start(plan: RunPlan): RunnerCommandResult {
        if (_state.value.phase.isBusy) {
            return RunnerCommandResult.Rejected(uiTextOf(R.string.msg_reject_already_running))
        }
        val executionId = UUID.randomUUID().toString()
        _state.value = RunnerState(
            phase = RunnerPhase.Preparing,
            activeExecution = ActiveExecution(
                executionId = executionId,
                runConfigurationId = plan.runConfigurationId,
                currentTaskName = null,
                completedTaskCount = 0,
                totalTaskCount = plan.tasks.size,
                taskResults = emptyList(),
                taskLabels = plan.taskLabelMap(),
            ),
            latestResult = null,
        )

        return withContext(MaaDispatchers.IO) {
            try {
                val rejection = launchOnService(plan, executionId)
                if (rejection != null) return@withContext failPreparation(rejection, executionId)
                // Stop 可能在 Preparing 窗口里已经把 phase 打成 Stopping，甚至 onFinished 已收回 Idle
                // 无条件写成 Running 会把停止意图丢掉，任务继续跑到结束
                val accepted = _state.updateAndGet { current ->
                    if (current.phase == RunnerPhase.Preparing) {
                        current.copy(phase = RunnerPhase.Running)
                    } else {
                        current
                    }
                }
                if (accepted.phase == RunnerPhase.Idle) {
                    RunnerCommandResult.Rejected(
                        accepted.latestResult.let { result ->
                            (result as? ExecutionResult.Failed)?.reason
                                ?: uiTextOf(R.string.msg_fail_default)
                        },
                    )
                } else {
                    RunnerCommandResult.Accepted
                }
            } catch (cancellation: CancellationException) {
                failPreparation(uiTextOf(R.string.msg_fail_default), executionId)
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Failed to start run")
                failPreparation(
                    uiTextFromFramework(throwable.message ?: throwable.javaClass.simpleName),
                    executionId,
                )
            }
        }
    }

    override suspend fun stop(): RunnerCommandResult {
        val phase = _state.value.phase
        if (!phase.isBusy) {
            // 幂等：没在跑也算受理，UI 不必先查状态
            return RunnerCommandResult.Accepted
        }
        _state.update { it.copy(phase = RunnerPhase.Stopping) }
        return withContext(MaaDispatchers.IO) {
            runCatching { servicePort.serviceOrNull()?.stopRun() }
                .fold(
                    onSuccess = { RunnerCommandResult.Accepted },
                    onFailure = {
                        Timber.w(it, "Failed to stop run")
                        RunnerCommandResult.Rejected(if (it.message.isNullOrBlank()) uiTextOf(R.string.msg_reject_stop_failed) else uiTextFromFramework(it.message))
                    },
                )
        }
    }

    override suspend fun acknowledgeModalFocus(focusId: String): Boolean =
        withContext(MaaDispatchers.IO) {
            runCatching { servicePort.serviceOrNull()?.acknowledgeModalFocus(focusId) == true }
                .getOrDefault(false)
        }

    /**
     * 返回 null 表示已受理，否则返回拒绝原因
     * 走 useService 而非取当前实例：它会先刷新授权状态、必要时发起授权请求，
     * 后端换了也会重新绑定
     */
    private suspend fun launchOnService(plan: RunPlan, executionId: String): UiText? {
        val piRoot = installer.installedDir()
        return servicePort.useService { service -> prepareAndStart(plan, piRoot, service, executionId) }
    }

    private fun prepareAndStart(
        plan: RunPlan,
        piRoot: File,
        service: RemoteService,
        executionId: String,
    ): UiText? {
        if (!service.setup(piRoot.absolutePath, AppPaths.LOG_DIR.absolutePath, debugMode())) {
            return uiTextOf(R.string.msg_reject_setup_failed)
        }
        // 调试模式：把 app + 特权进程的 logcat 抓到 external/debug/logcat（对齐 MaaMeow）。
        // 跟主服务同后端；bind 只在首次生效，startCapture 对已抓的 pid 是空操作
        if (debugMode()) {
            scope.launch(MaaDispatchers.IO) {
                runCatching {
                    val backend = servicePort.currentBackend ?: return@runCatching
                    LogcatServiceManager.bind(backend)
                    LogcatServiceManager.startCapture(
                        appPid = Process.myPid(),
                        servicePid = service.pid(),
                        userDir = AppPaths.LOG_DIR.parentFile!!.absolutePath,
                    )
                }.onFailure { Timber.w(it, "LogcatService startCapture failed") }
            }
        }
        val mode = runMode()
        if (!service.setVirtualDisplayMode(mode.displayMode)) {
            return uiTextOf(R.string.msg_reject_switch_display_mode, mode)
        }
        // 主屏模式不建屏也不设分辨率：尺寸是设备当下的物理尺寸，由特权进程侧的采集器供数
        val (width, height) = if (mode == RunMode.BACKGROUND) {
            resolutionPreference().resolution.also { (w, h) ->
                service.setVirtualDisplayResolution(w, h, DefaultDisplayConfig.DPI)
            }
        } else {
            DisplayResolution(0, 0)
        }
        if (service.startVirtualDisplay() == DefaultDisplayConfig.DISPLAY_NONE) {
            return if (mode == RunMode.FOREGROUND) uiTextOf(R.string.msg_reject_primary_capture) else uiTextOf(R.string.msg_reject_virtual_display)
        }

        bindRunnerCallback(service)

        val payload = RunPlanPayload(
            executionId = executionId,
            resourcePaths = plan.resource.paths.map { File(piRoot, it).absolutePath },
            screenWidth = width,
            screenHeight = height,
            displayMode = mode.displayMode,
            tasks = plan.tasks.map {
                RuntimeTaskPayload(
                    taskName = it.taskName,
                    entry = it.entry,
                    pipelineOverrides = it.pipelineOverrides,
                )
            },
            agents = plan.agents.map { AgentPayload(it.childExec, it.childArgs) },
            apkPath = apkPath,
            nativeLibraryDir = nativeLibraryDir,
            piEnv = plan.piEnv,
        )
        if (!service.startRun(runPlanWireJson.encodeToString(payload))) {
            return uiTextOf(R.string.msg_reject_service_rejected)
        }
        if (shouldRetryStop(executionId)) {
            runCatching { service.stopRun() }
                .onFailure { Timber.w(it, "Failed to retry stop after run start") }
        }
        return null
    }

    private fun shouldRetryStop(executionId: String): Boolean {
        val current = _state.value
        return current.phase == RunnerPhase.Stopping &&
            current.activeExecution?.executionId == executionId
    }

    /**
     * 准备失败才把 phase 收回 Idle；onFinished / abort 已经写过终态的不要盖掉
     */
    private fun failPreparation(reason: UiText, executionId: String): RunnerCommandResult {
        val next = _state.updateAndGet { current ->
            if (current.phase == RunnerPhase.Preparing) {
                RunnerState(phase = RunnerPhase.Idle, latestResult = ExecutionResult.Failed(reason))
            } else if (current.phase == RunnerPhase.Stopping &&
                current.activeExecution?.executionId == executionId
            ) {
                RunnerState(phase = RunnerPhase.Idle, latestResult = ExecutionResult.Cancelled(emptyList()))
            } else {
                current
            }
        }
        val rejected = (next.latestResult as? ExecutionResult.Failed)?.reason ?: reason
        return RunnerCommandResult.Rejected(rejected)
    }

    /**
     * 原样转成事件，两段分开带；按前缀分类是消费方的事，这里不预先拼串也不丢字段
     *
     * PI 声明了模板就只投递模板：那句话是作者写给用户的，同一条回调再刷一份原始转储只是噪音
     */
    private fun toRunnerEvent(message: String, detailsJson: String): RunnerEvent {
        if (message.isEmpty()) return RunnerEvent.MalformedCallback(detailsJson)
        FocusParser.parse(message, detailsJson)?.let { return RunnerEvent.Focus(it) }
        return RunnerEvent.Callback(message, detailsJson)
    }

    internal fun modalFocusEvent(
        executionId: String?,
        focusId: String?,
        message: String,
        detailsJson: String,
    ): RunnerEvent {
        val focus = FocusParser.parse(message, detailsJson)
        val event = focus?.copy(
            modalId = focusId?.takeIf(String::isNotBlank),
            executionId = executionId?.takeIf(String::isNotBlank),
        )
        return event?.let(RunnerEvent::Focus) ?: toRunnerEvent(message, detailsJson)
    }

    private companion object {
        /** 对账间隔：Running / Stopping 期问，问一次是一次 binder 往返，不必更密 */
        const val RECONCILE_INTERVAL_MS = 5_000L

        /**
         * 一次否定读数之后的宽限
         *
         * 特权进程收尾时先收回 running 再发 onFinished，中间那一瞬问到的就是
         * 「没在跑」。onFinished 是 oneway 调用，微秒级就该落地，等这么久足够分辨
         */
        const val RECONCILE_GRACE_MS = 2_000L
    }
}
