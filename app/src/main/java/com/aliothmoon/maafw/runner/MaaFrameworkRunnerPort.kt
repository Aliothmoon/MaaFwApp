package com.aliothmoon.maafw.runner

import android.os.Process
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.LogcatServiceManager
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.project.PiInstaller
import kotlinx.coroutines.CoroutineDispatcher
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
    /** MaaFramework 的 maa.log 与 Screencap 动作的落点；须是特权进程（shell/root 身份）可写的目录 */
    private val logDir: () -> File,
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
    private val ioDispatcher: CoroutineDispatcher,
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
                    abortRun("特权进程已退出", "特权进程在执行期间死亡，强制收回执行态")
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
     * 只在 Running 期问：Preparing 时 startRun 还没发出去，那边本来就没在跑
     */
    private suspend fun reconcileWhileRunning() {
        _state.map { it.phase }.distinctUntilChanged().collectLatest { phase ->
            if (phase != RunnerPhase.Running) return@collectLatest
            while (true) {
                delay(RECONCILE_INTERVAL_MS)
                if (remoteRunning() != false) continue
                delay(RECONCILE_GRACE_MS)
                // onFinished 在宽限期内落地了，一切正常
                if (!_state.value.phase.isBusy) return@collectLatest
                if (remoteRunning() != false) continue
                abortRun("特权进程报告本轮已结束", "对账发现执行早已结束但结果没回来，强制收回执行态")
                return@collectLatest
            }
        }
    }

    /** null = 问不出来（没连上或调用失败），不能据此判定，只有明确的 false 才算数 */
    private suspend fun remoteRunning(): Boolean? = withContext(ioDispatcher) {
        runCatching { servicePort.serviceOrNull()?.isRunning() }.getOrNull()
    }

    /**
     * 用 getAndUpdate 原子判并换：真正的 onFinished 可能刚好抢在前头落地，
     * 那一份结果比这里编的准，不能覆盖
     */
    private fun abortRun(resultReason: String, logMessage: String) {
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

    private val callback = object : IMaaRunnerCallback.Stub() {
        override fun onEvent(message: String?, detailsJson: String?) {
            _events.tryEmit(toRunnerEvent(message.orEmpty(), detailsJson.orEmpty()))
        }

        override fun onAgentOutput(line: String?, fromStderr: Boolean) {
            _events.tryEmit(RunnerEvent.AgentOutput(line.orEmpty(), fromStderr))
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
                else -> ExecutionResult.Failed(reason.orEmpty().ifEmpty { "执行失败" }, results)
            }
            _state.value = RunnerState(phase = RunnerPhase.Idle, latestResult = result)
        }
    }

    override suspend fun start(plan: RunPlan): RunnerCommandResult {
        if (_state.value.phase.isBusy) {
            return RunnerCommandResult.Rejected("已有执行在进行中")
        }
        _state.value = RunnerState(
            phase = RunnerPhase.Preparing,
            activeExecution = ActiveExecution(
                executionId = UUID.randomUUID().toString(),
                runConfigurationId = plan.runConfigurationId,
                currentTaskName = null,
                completedTaskCount = 0,
                totalTaskCount = plan.tasks.size,
                taskResults = emptyList(),
            ),
            latestResult = null,
        )

        return withContext(ioDispatcher) {
            runCatching { launchOnService(plan) }
                .fold(
                    onSuccess = { rejection ->
                        if (rejection == null) {
                            _state.update { it.copy(phase = RunnerPhase.Running) }
                            RunnerCommandResult.Accepted
                        } else {
                            failPreparation(rejection)
                        }
                    },
                    onFailure = { throwable ->
                        Timber.e(throwable, "Failed to start run")
                        failPreparation(throwable.message ?: throwable.javaClass.simpleName)
                    },
                )
        }
    }

    override suspend fun stop(): RunnerCommandResult {
        val phase = _state.value.phase
        if (!phase.isBusy) {
            // 幂等：没在跑也算受理，UI 不必先查状态
            return RunnerCommandResult.Accepted
        }
        _state.update { it.copy(phase = RunnerPhase.Stopping) }
        return withContext(ioDispatcher) {
            runCatching { servicePort.serviceOrNull()?.stopRun() }
                .fold(
                    onSuccess = { RunnerCommandResult.Accepted },
                    onFailure = {
                        Timber.w(it, "Failed to stop run")
                        RunnerCommandResult.Rejected(it.message ?: "停止失败")
                    },
                )
        }
    }

    /**
     * 返回 null 表示已受理，否则返回拒绝原因
     * 走 useService 而非取当前实例：它会先刷新授权状态、必要时发起授权请求，
     * 后端换了也会重新绑定
     */
    private suspend fun launchOnService(plan: RunPlan): String? {
        val piRoot = installer.ensureInstalled()
        return servicePort.useService { service -> prepareAndStart(plan, piRoot, service) }
    }

    private fun prepareAndStart(plan: RunPlan, piRoot: File, service: RemoteService): String? {
        if (!service.setup(piRoot.absolutePath, logDir().absolutePath, debugMode())) {
            return "特权进程 setup 失败"
        }
        // 调试模式：把 app + 特权进程的 logcat 抓到 external/debug/logcat（对齐 MaaMeow）。
        // 跟主服务同后端；bind 只在首次生效，startCapture 对已抓的 pid 是空操作
        if (debugMode()) {
            scope.launch(ioDispatcher) {
                runCatching {
                    val backend = servicePort.currentBackend ?: return@runCatching
                    LogcatServiceManager.bind(backend)
                    LogcatServiceManager.startCapture(
                        appPid = Process.myPid(),
                        servicePid = service.pid(),
                        userDir = logDir().parentFile!!.absolutePath,
                    )
                }.onFailure { Timber.w(it, "LogcatService startCapture failed") }
            }
        }
        val mode = runMode()
        if (!service.setVirtualDisplayMode(mode.displayMode)) {
            return "切换显示模式失败: $mode"
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
            return if (mode == RunMode.FOREGROUND) "主屏采集启动失败" else "虚拟显示器启动失败"
        }

        service.setRunnerCallback(callback)

        val payload = RunPlanPayload(
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
        )
        if (!service.startRun(runPlanWireJson.encodeToString(payload))) {
            return "特权进程拒绝了本次执行"
        }
        return null
    }

    private fun failPreparation(reason: String): RunnerCommandResult {
        _state.value = RunnerState(
            phase = RunnerPhase.Idle,
            latestResult = ExecutionResult.Failed(reason),
        )
        return RunnerCommandResult.Rejected(reason)
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

    private companion object {
        /** 对账间隔：只在 Running 期问，问一次是一次 binder 往返，不必更密 */
        const val RECONCILE_INTERVAL_MS = 5_000L

        /**
         * 一次否定读数之后的宽限
         *
         * 特权进程收尾时先 `running.set(false)` 再发 onFinished，中间那一瞬问到的就是
         * 「没在跑」。onFinished 是 oneway 调用，微秒级就该落地，等这么久足够分辨
         */
        const val RECONCILE_GRACE_MS = 2_000L
    }
}
