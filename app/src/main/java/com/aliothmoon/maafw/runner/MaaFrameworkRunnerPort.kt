package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.project.PiInstaller
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import android.content.res.Resources
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RunnerPort 的真实实现：本对象跑在 app 进程，MaaFramework 实例在特权进程里，两者经 binder 通信
 * 这里不持有任何 native handle，也不理解 C API 调用序列（docs/privileged-runtime.md §6）
 */
class MaaFrameworkRunnerPort(
    private val installer: PiInstaller,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val serviceManager: RemoteServiceManager = RemoteServiceManager,
) : RunnerPort {

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RunnerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RunnerEvent> = _events.asSharedFlow()

    /** 当前这轮的任务名，按 RunPlan 顺序；onTaskFinished 靠它算进度 */
    private val activeTasks = AtomicReference<List<String>>(emptyList())

    private val callback = object : IMaaRunnerCallback.Stub() {
        override fun onEvent(message: String?, detailsJson: String?) {
            _events.tryEmit(toRunnerEvent(message.orEmpty(), detailsJson.orEmpty()))
        }

        override fun onTaskStarted(taskName: String?, index: Int, total: Int) {
            val name = taskName.orEmpty()
            _state.update { current ->
                current.copy(
                    activeExecution = current.activeExecution?.copy(
                        currentTaskName = name,
                        completedTaskCount = index,
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
                current.copy(
                    activeExecution = execution.copy(
                        completedTaskCount = execution.completedTaskCount + 1,
                        taskResults = execution.taskResults + result,
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
            activeTasks.set(emptyList())
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
                        Timber.e(throwable, "启动执行失败")
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
            runCatching { serviceManager.getInstanceOrNull()?.stopRun() }
                .fold(
                    onSuccess = { RunnerCommandResult.Accepted },
                    onFailure = {
                        Timber.w(it, "停止执行失败")
                        RunnerCommandResult.Rejected(it.message ?: "停止失败")
                    },
                )
        }
    }

    /**
     * 返回 null 表示已受理，否则返回拒绝原因
     * 走 useRemoteService 而非 getInstance：它会先刷新授权状态、必要时发起授权请求，
     * 后端换了也会重新绑定
     */
    private suspend fun launchOnService(plan: RunPlan): String? {
        val piRoot = installer.ensureInstalled()
        return serviceManager.useRemoteService { service -> prepareAndStart(plan, piRoot, service) }
    }

    private fun prepareAndStart(plan: RunPlan, piRoot: File, service: RemoteService): String? {
        if (!service.setup(piRoot.absolutePath, BuildConfig.DEBUG)) {
            return "特权进程 setup 失败"
        }
        if (!service.setVirtualDisplayMode(DisplayMode.BACKGROUND)) {
            return "切换后台虚拟屏失败"
        }
        val (width, height) = resolveResolution(plan.controller)
        service.setVirtualDisplayResolution(width, height, DefaultDisplayConfig.DPI)
        if (service.startVirtualDisplay() == DefaultDisplayConfig.DISPLAY_NONE) {
            return "虚拟显示器启动失败"
        }

        service.setRunnerCallback(callback)
        activeTasks.set(plan.tasks.map { it.taskName })

        val payload = RunPlanPayload(
            resourcePaths = plan.resource.paths.map { File(piRoot, it).absolutePath },
            screenWidth = width,
            screenHeight = height,
            tasks = plan.tasks.map {
                RuntimeTaskPayload(
                    taskName = it.taskName,
                    entry = it.entry,
                    pipelineOverrides = it.pipelineOverrides,
                )
            },
        )
        if (!service.startRun(runPlanWireJson.encodeToString(payload))) {
            return "特权进程拒绝了本次执行"
        }
        return null
    }

    /**
     * 由 PI controller 的 display_* 声明推导虚拟屏分辨率（docs/privileged-runtime.md §5）
     * 官方语义是「截图缩放到该边长」；这里是自己建屏，直接按目标边长建，省掉再缩放一次
     *
     * 方向固定横屏：虚拟屏与设备旋转无关，PI 的模板一般按横屏截取——这是本项目的假设，不是协议规定
     * 边长取偶数：奇数宽在部分编码器上会导致 stride 与预期不符
     */
    private fun resolveResolution(controller: ControllerDefinition): Pair<Int, Int> {
        val metrics = Resources.getSystem().displayMetrics
        val rawLong = max(metrics.widthPixels, metrics.heightPixels)
        val rawShort = min(metrics.widthPixels, metrics.heightPixels)
        if (controller.displayRaw || rawShort <= 0) {
            return rawLong.alignEven() to rawShort.alignEven()
        }
        val aspect = rawLong.toDouble() / rawShort
        controller.displayLongSide?.takeIf { it > 0 }?.let { long ->
            return long.alignEven() to (long / aspect).roundToInt().alignEven()
        }
        val short = controller.displayShortSide?.takeIf { it > 0 } ?: DEFAULT_SHORT_SIDE
        return (short * aspect).roundToInt().alignEven() to short.alignEven()
    }

    private fun Int.alignEven(): Int = this and 1.inv()

    private fun failPreparation(reason: String): RunnerCommandResult {
        _state.value = RunnerState(
            phase = RunnerPhase.Idle,
            latestResult = ExecutionResult.Failed(reason),
        )
        return RunnerCommandResult.Rejected(reason)
    }

    /** MaaFramework 的通知只做粗分派；节点级细节留在 details_json 里由日志消费 */
    private fun toRunnerEvent(message: String, detailsJson: String): RunnerEvent = when {
        message.isEmpty() -> RunnerEvent.MalformedCallback(detailsJson)
        message.startsWith(NODE_PREFIX) -> RunnerEvent.TaskObservation(message, detailsJson)
        message.startsWith(TASKER_PREFIX) ||
            message.startsWith(RESOURCE_PREFIX) ||
            message.startsWith(CONTROLLER_PREFIX) -> RunnerEvent.Log("$message $detailsJson")

        else -> RunnerEvent.Unknown("$message $detailsJson")
    }

    private companion object {
        /** PI V2 的 display_short_side 默认值 */
        const val DEFAULT_SHORT_SIDE = 720

        const val NODE_PREFIX = "Node."
        const val TASKER_PREFIX = "Tasker."
        const val RESOURCE_PREFIX = "Resource."
        const val CONTROLLER_PREFIX = "Controller."
    }
}
