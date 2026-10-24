package com.aliothmoon.maafw.runner

import android.os.Process
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.ITextInputSink
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
import com.aliothmoon.maafw.project.SlotWorkspace
import com.aliothmoon.maafw.remote.AgentRuntimeDescriptor
import com.aliothmoon.maafw.remote.AgentRuntimeEntry
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RunnerPort 的真实实现：本对象跑在 app 进程，MaaFramework 实例在特权进程里，两者经 binder 通信
 * 这里不持有任何 native handle，也不理解 C API 调用序列（docs/privileged-runtime.md §6）
 *
 * 多开下的分工：**每路一套执行态**（[stateBySlot]），一次运行归它发起时的那一路。
 * 对外暴露的 [state] 只看当前选中路，所以第 1 路跑着时切到第 2 路，界面显示的是
 * 第 2 路的空闲态——而不是把第 1 路的运行态冒充成第 2 路的，那个会让「停止」按钮
 * 出现在一个根本没跑的路上面
 */
class MaaFrameworkRunnerPort(
    private val installer: PiInstaller,
    /** 本包 APK 的绝对路径；特权进程从中读 agent 运行时的 assets */
    private val apkPath: String,
    /** 已解压的 native 库目录；agent child 靠它 dlopen libMaaAgentServer.so 及其依赖 */
    private val nativeLibraryDir: String,
    /** 每轮开始时现读，不缓存：用户可能在两轮之间改了运行模式 */
    private val runMode: () -> RunMode,
    /** 同上：分辨率预设可能两轮之间被改 */
    private val resolutionPreset: () -> ResolutionPreset,
    /** 调试模式：传给特权进程 setup 的 isDebug，开启 MaaFramework 详细日志 */
    private val debugMode: () -> Boolean,
    /** 同上：出错存图可能两轮之间被改，每轮 setup 后现读 */
    private val saveOnError: () -> Boolean,
    private val scope: CoroutineScope,
    private val servicePort: PrivilegedServicePort,
    /** 取值函数而不是实例：sink → RunJournal → FocusDispatcher → RunnerPort 成环，构造时解析会栈溢出 */
    private val textInputSink: () -> ITextInputSink,
) : RunnerPort {

    // ── 执行态：按路分份 ──

    /**
     * 每路一套执行态，键是路号
     *
     * 回调与对账必须按「发起那次运行的路」记账，不能读当前选中路：否则第 1 路跑着、
     * 用户切到第 2 路，第 1 路的结果会写进第 2 路的界面，而第 1 路的 phase 永远停在 Running
     */
    private val stateBySlot = ConcurrentHashMap<Int, MutableStateFlow<RunnerState>>()

    private fun stateFor(slot: Int): MutableStateFlow<RunnerState> =
        stateBySlot.computeIfAbsent(slot) { MutableStateFlow(RunnerState()) }

    /** 当前选中路的执行态；面向「一次动作」的读写都走它 */
    private val currentState: MutableStateFlow<RunnerState>
        get() = stateFor(servicePort.activeSlot.value)

    /**
     * 对外只暴露当前选中路的进度；切路后订阅自动挪到新路的那个流上
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<RunnerState> =
        servicePort.activeSlot
            .flatMapLatest { slot -> stateFor(slot) }
            .stateIn(scope, SharingStarted.Eagerly, RunnerState())

    /** 钉死在某一路的执行态；收尾协程按发起那轮的 slot 等终局，不受随后切路影响 */
    override fun stateOfSlot(slot: Int): StateFlow<RunnerState> = stateFor(slot)

    private val _events = MutableSharedFlow<RunnerEventEnvelope>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RunnerEventEnvelope> = _events.asSharedFlow()

    /**
     * 配方给 agent 起的显示名，按序号对应；与特权进程读的是同一个 APK 里的同一份描述
     * 在 app 侧就地查而不经 binder 传：回调的签名一改，挺过升级的旧特权进程就会解错参数
     */
    private val agentRuntimes: List<AgentRuntimeEntry> by lazy {
        runCatching { AgentRuntimeDescriptor.readFromApk(apkPath)?.runtimes }
            .onFailure { Timber.w(it, "agent runtime descriptor unreadable, agent names fall back to executables") }
            .getOrNull()
            .orEmpty()
    }

    private fun agentName(index: Int): String? = agentRuntimes.getOrNull(index)?.name

    /** 细节所在的特权进程 logcat 只有调试模式才落盘，没开就提示去打开 */
    private fun agentLaunchFailure(index: Int?): UiText {
        val failed = index?.let {
            val entry = agentRuntimes.getOrNull(it)
            uiTextOf(R.string.msg_fail_agent_launch, agentLabel(entry?.name, entry?.executable.orEmpty(), it))
        } ?: uiTextOf(R.string.msg_fail_agent_launch_any)
        return if (debugMode()) failed else uiTextOf(R.string.msg_fail_debug_hint, failed)
    }

    init {
        // 特权进程死了 onFinished 就永远不会来，phase 卡在 Running，configurationLocked
        // 跟着卡死，UI 全灰且再也发不起下一轮。只认 Died：主动 unbind 只是断了 app 这侧的
        // binder，远端仍握着我们的 callback stub，那一轮还在跑、结果照样会回来
        //
        // 多开下 serviceState 跟的是当前选中路，这里收回的也就是那一路的执行态；
        // 别的路的进程各自活着，不该被牵连
        scope.launch {
            servicePort.serviceState.collect { serviceState ->
                if (serviceState == PrivilegedServiceState.Died) {
                    abortRun(
                        servicePort.activeSlot.value,
                        uiTextOf(R.string.msg_fail_privileged_exited),
                        "privileged process died mid-run, forcing abort",
                    )
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
     *
     * 多开下逐路扫：每一路各跑各的，只盯当前选中路会让切走的那一路卡在 Running 到天荒地老
     */
    private suspend fun reconcileWhileRunning() {
        while (true) {
            delay(RECONCILE_INTERVAL_MS)
            for (slot in stateBySlot.keys.toList()) {
                val flow = stateFor(slot)
                val phase = flow.value.phase
                if (phase != RunnerPhase.Running && phase != RunnerPhase.Stopping) continue
                if (remoteRunning(slot) != false) continue
                delay(RECONCILE_GRACE_MS)
                // onFinished 在宽限期内落地了，一切正常
                if (!flow.value.phase.isBusy) continue
                if (remoteRunning(slot) != false) continue
                abortRun(
                    slot,
                    uiTextOf(R.string.msg_fail_privileged_reported_finished),
                    "reconciliation found run already finished but result missing, forcing abort (slot=$slot)",
                )
            }
        }
    }

    /** null = 问不出来（没连上或调用失败），不能据此判定，只有明确的 false 才算数 */
    private suspend fun remoteRunning(slot: Int): Boolean? = withContext(MaaDispatchers.IO) {
        runCatching { servicePort.serviceOrNull(slot)?.isRunning() }.getOrNull()
    }

    /**
     * 用 getAndUpdate 原子判并换：真正的 onFinished 可能刚好抢在前头落地，
     * 那一份结果比这里编的准，不能覆盖
     */
    private fun abortRun(slot: Int, resultReason: UiText, logMessage: String) {
        val flow = stateFor(slot)
        val previous = flow.getAndUpdate { current ->
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
        if (previous.phase.isBusy) {
            previous.activeExecution?.let {
                val result = ExecutionResult.Failed(resultReason, it.taskResults)
                _events.tryEmit(
                    RunnerEventEnvelope(
                        it.executionId,
                        it.currentTaskLabel,
                        RunnerEvent.ExecutionFinished(result),
                        slot,
                    ),
                )
            }
            Timber.w(logMessage)
        }
    }

    /**
     * JVM 单测里 [IMaaRunnerCallback.Stub] 会调未 mock 的 Binder.attachInterface
     * 测 start/stop/对账时换成空操作，避免为测 phase 去构造 AIDL Stub
     */
    internal var bindRunnerCallback: (RemoteService, ExecutionCallback) -> Unit = { service, callback ->
        service.setRunnerCallback(
            object : IMaaRunnerCallback.Stub() {
                override fun onEvent(message: String?, detailsJson: String?) =
                    callback.onEvent(message, detailsJson)

                override fun onAgentOutput(line: String?, fromStderr: Boolean) =
                    callback.onAgentOutput(line, fromStderr)

                override fun onAgentConnected(index: Int, total: Int, exec: String?) =
                    callback.onAgentConnected(index, total, exec)

                override fun onAgentExited(index: Int, exec: String?, exitCode: Int, crashReport: String?) =
                    callback.onAgentExited(index, exec, exitCode, crashReport)

                override fun onTaskStarted(taskName: String?, index: Int, total: Int) =
                    callback.onTaskStarted(taskName, index, total)

                override fun onTaskFinished(taskName: String?, success: Boolean, message: String?, cancelled: Boolean) =
                    callback.onTaskFinished(taskName, success, message, cancelled)

                override fun onFinished(outcome: Int, reason: String?) =
                    callback.onFinished(outcome, reason)
            },
        )
    }

    /** 按轮新建，排队中的旧轮调用改不动新一轮；同一 Stub 的 oneway 调用按序到达，收到时的 [taskLabel] 就是事件所属任务 */
    internal inner class ExecutionCallback(
        /** 这次执行落在哪一路；回调只动那一路的执行态 */
        private val slot: Int,
        private val executionId: String,
        private val taskLabels: Map<String, String>,
    ) {
        /** 本轮自己的执行态，与「当前选中路」无关 */
        private val own: MutableStateFlow<RunnerState> get() = stateFor(slot)

        @Volatile
        private var taskLabel: String? = null

        @Volatile
        private var taskIndex: Int = -1

        fun onEvent(message: String?, detailsJson: String?) {
            emit(toRunnerEvent(message.orEmpty(), detailsJson.orEmpty()))
        }

        fun onAgentOutput(line: String?, fromStderr: Boolean) {
            emit(RunnerEvent.AgentOutput(line.orEmpty(), fromStderr))
        }

        fun onAgentConnected(index: Int, total: Int, exec: String?) {
            emit(RunnerEvent.AgentConnected(index, total, exec.orEmpty(), agentName(index)))
        }

        fun onAgentExited(index: Int, exec: String?, exitCode: Int, crashReport: String?) {
            emit(RunnerEvent.AgentExited(index, exec.orEmpty(), exitCode, crashReport, agentName(index)))
        }

        // 不碰 completedTaskCount：那是 onTaskFinished 的账，两边各记一套会在丢事件时永久漂
        fun onTaskStarted(taskName: String?, index: Int, total: Int) {
            val name = taskName.orEmpty()
            taskLabel = taskLabels[name]?.takeIf(String::isNotBlank) ?: name
            taskIndex = index
            updateOwn { it.copy(currentTaskName = name, totalTaskCount = total) }
            emit(RunnerEvent.Progress(name, index, total))
        }

        fun onTaskFinished(taskName: String?, success: Boolean, message: String?, cancelled: Boolean) {
            val result = TaskResult(taskName.orEmpty(), success, message)
            updateOwn { execution ->
                val results = execution.taskResults + result
                execution.copy(completedTaskCount = results.size, taskResults = results)
            }
            emit(RunnerEvent.TaskFinished(taskIndex, success, cancelled))
        }

        fun onFinished(outcome: Int, reason: String?) {
            // 已被对账或死亡通知收回的那轮，终局 marker 在 abortRun 里发过了
            val execution = own.value.activeExecution?.takeIf { it.executionId == executionId } ?: return
            // 同一 Stub 的回调按序到达，此刻 taskResults 已是全量，marker 与 state 用同一份结局
            val results = execution.taskResults
            val result = when (outcome) {
                RunOutcome.COMPLETED -> ExecutionResult.Completed(results)
                RunOutcome.COMPLETED_WITH_FAILURES -> ExecutionResult.CompletedWithFailures(results)
                RunOutcome.CANCELLED -> ExecutionResult.Cancelled(results)
                RunOutcome.AGENT_LAUNCH_FAILED -> ExecutionResult.Failed(agentLaunchFailure(reason?.toIntOrNull()), results)
                else -> ExecutionResult.Failed(if (reason.isNullOrBlank()) uiTextOf(R.string.msg_fail_default) else uiTextFromFramework(reason), results)
            }
            emit(RunnerEvent.ExecutionFinished(result))
            own.update { current ->
                if (current.activeExecution?.executionId != executionId) return@update current
                RunnerState(phase = RunnerPhase.Idle, latestResult = result)
            }
        }

        private inline fun updateOwn(crossinline transform: (ActiveExecution) -> ActiveExecution) {
            own.update { current ->
                val execution = current.activeExecution
                if (execution?.executionId != executionId) return@update current
                current.copy(activeExecution = transform(execution))
            }
        }

        private fun emit(event: RunnerEvent) {
            // slot 冻在信封上：日志按它分桶，「全部实例」那一屏才能各显示各自的几行
            _events.tryEmit(RunnerEventEnvelope(executionId, taskLabel, event, slot))
        }
    }

    override suspend fun start(plan: RunPlan, executionId: String, slot: Int): RunnerCommandResult {
        // 这一轮归「按下开始那一刻」的路；slot 由编排层在 dispatch 一开始冻结后传入，
        // 执行途中（含亮屏解锁、倒计时等耗时 hook）切路都不影响它落到哪一路
        val own = stateFor(slot)
        if (own.value.phase.isBusy) {
            return RunnerCommandResult.Rejected(uiTextOf(R.string.msg_reject_already_running))
        }
        own.value = RunnerState(
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
                val rejection = launchOnService(plan, executionId, slot)
                if (rejection != null) return@withContext failPreparation(rejection, executionId, slot)
                // Stop 可能在 Preparing 窗口里已经把 phase 打成 Stopping，甚至 onFinished 已收回 Idle
                // 无条件写成 Running 会把停止意图丢掉，任务继续跑到结束
                val accepted = own.updateAndGet { current ->
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
                failPreparation(uiTextOf(R.string.msg_fail_default), executionId, slot)
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Failed to start run")
                failPreparation(
                    uiTextFromFramework(throwable.message ?: throwable.javaClass.simpleName),
                    executionId,
                    slot,
                    error = throwable,
                )
            }
        }
    }

    override suspend fun stop(): RunnerCommandResult {
        // 停的永远是「当前选中路」：切到第 2 路按停止，停的是第 2 路，第 1 路照跑
        val own = currentState
        val phase = own.value.phase
        if (!phase.isBusy) {
            // 幂等：没在跑也算受理，UI 不必先查状态
            return RunnerCommandResult.Accepted
        }
        own.update { it.copy(phase = RunnerPhase.Stopping) }
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

    /**
     * 返回 null 表示已受理，否则返回拒绝原因
     * 走 useService 而非取当前实例：它会先刷新授权状态、必要时发起授权请求，
     * 后端换了也会重新绑定
     */
    private suspend fun launchOnService(plan: RunPlan, executionId: String, slot: Int): UiText? {
        val piRoot = installer.installedDir()
        // agent 的 cwd 按路分开：它会在里面写 config/、.venv/ 与动作脚本的产物，
        // 共用一份时两路互相覆盖（一路存的账号另一路看不出来）。MaaFramework 的资源路径
        // 仍旧指向共用 PI，识别模板只存一份、改一处所有实例立刻生效
        val workRoot = withContext(MaaDispatchers.IO) {
            runCatching { SlotWorkspace.ensureInstalled(piRoot, slot) }
                .onFailure { Timber.w(it, "SlotWorkspace 准备失败，回退到共用 PI 根") }
                .getOrDefault(piRoot)
        }
        return servicePort.useService(slot) { service ->
            prepareAndStart(plan, piRoot, workRoot, service, executionId, slot)
        }
    }

    /** [piRoot] 供 MaaFramework 读资源；[workRoot] 是该路 agent 的 cwd——读共用、写分家 */
    private fun prepareAndStart(
        plan: RunPlan,
        piRoot: File,
        workRoot: File,
        service: RemoteService,
        executionId: String,
        slot: Int,
    ): UiText? {
        // 调试模式：把 app + 特权进程的 logcat 抓到 external/debug/logcat（对齐 MaaMeow）。
        // 跟主服务同后端；bind 只在首次生效，startCapture 对已抓的 pid 是空操作。
        // 排在 setup 之前：它不依赖 setup，而 setup 失败正是最需要现场的时候
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
        if (!service.setup(workRoot.absolutePath, AppPaths.LOG_DIR.absolutePath, debugMode())) {
            return uiTextOf(R.string.msg_reject_setup_failed)
        }
        // 环境性开关，不作为拒跑理由：设不上只是不存图，下一轮再试
        runCatching { service.setSaveOnError(saveOnError()) }
            .onFailure { Timber.w(it, "setSaveOnError failed") }
        runCatching { service.setTextInputSink(textInputSink()) }
            .onFailure { Timber.w(it, "setTextInputSink failed") }
        val mode = runMode()
        if (!service.setVirtualDisplayMode(mode.displayMode)) {
            return uiTextOf(R.string.msg_reject_switch_display_mode, mode)
        }
        // 主屏模式不建屏也不设分辨率：尺寸是设备当下的物理尺寸，由特权进程侧的采集器供数
        val (width, height) = if (mode == RunMode.BACKGROUND) {
            val preset = resolutionPreset()
            preset.resolution.also { (w, h) ->
                service.setVirtualDisplayResolution(w, h, preset.dpi)
            }
        } else {
            DisplayResolution(0, 0)
        }
        if (service.startVirtualDisplay() == DefaultDisplayConfig.DISPLAY_NONE) {
            return if (mode == RunMode.FOREGROUND) uiTextOf(R.string.msg_reject_primary_capture) else uiTextOf(R.string.msg_reject_virtual_display)
        }

        bindRunnerCallback(service, ExecutionCallback(slot, executionId, plan.taskLabelMap()))

        val payload = RunPlanPayload(
            // 资源始终取共用 PI：模板只存一份，改一处所有实例立刻生效
            resourcePaths = plan.resourceBundlePaths().map { File(piRoot, it).absolutePath },
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
        if (shouldRetryStop(executionId, slot)) {
            runCatching { service.stopRun() }
                .onFailure { Timber.w(it, "Failed to retry stop after run start") }
        }
        return null
    }

    /** 查的是「这一轮所属那一路」的态：执行途中切了路也要认自己那一轮 */
    private fun shouldRetryStop(executionId: String, slot: Int): Boolean {
        val current = stateFor(slot).value
        return current.phase == RunnerPhase.Stopping &&
            current.activeExecution?.executionId == executionId
    }

    /**
     * 准备失败才把 phase 收回 Idle；onFinished / abort 已经写过终态的不要盖掉
     */
    private fun failPreparation(
        reason: UiText,
        executionId: String,
        slot: Int,
        error: Throwable? = null,
    ): RunnerCommandResult {
        val next = stateFor(slot).updateAndGet { current ->
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
        return RunnerCommandResult.Rejected(rejected, error)
    }

    /**
     * 原样转成事件，两段分开带；按前缀分类是消费方的事，这里不预先拼串也不丢字段
     *
     * PI 声明了模板就只投递模板：那句话是作者写给用户的，同一条回调再刷一份原始转储只是噪音
     */
    private fun toRunnerEvent(message: String, detailsJson: String): RunnerEvent {
        if (message.isEmpty()) return RunnerEvent.MalformedCallback(detailsJson)
        FocusParser.parse(message, detailsJson)?.let { return RunnerEvent.Focus(it, detailsJson) }
        return RunnerEvent.Callback(message, detailsJson)
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
