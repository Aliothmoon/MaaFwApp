package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.flow.first

/**
 * 执行期间把 app 进程钉住的手段
 *
 * 抽成接口只为让 [RunLauncher] 不必认识 Context 与 Service；
 * 生产实现是 [com.aliothmoon.maafw.service.ForegroundRunKeepAlive]
 */
fun interface RunKeepAlive {
    fun start()
}

/** [RunLauncher.launch] 的结局；文案由调用方挑，这里只给分类 */
sealed interface RunLaunchResult {
    /** 已受理；进度与终态看 [RunnerPort.state] */
    data object Started : RunLaunchResult

    /** 项目还在加载或加载失败，没有可编译的 Definition */
    data object ProjectNotReady : RunLaunchResult
    data object NoExecutableTasks : RunLaunchResult
    data class Invalid(val diagnostics: List<Diagnostic>) : RunLaunchResult

    /** RunnerPort 拒绝；已有执行在跑时走这条 */
    data class Rejected(val reason: String) : RunLaunchResult
}

/**
 * 发起一轮执行：读项目 → 编译 RunPlan → 交给 [RunnerPort] → 拉起保活
 *
 * 这四步没有一步需要 UI 状态，所以它是进程级的而不是 ViewModel 级的。定时触发落在
 * Service 里，那里既没有 ViewModelStoreOwner 也拿不到 Activity 作用域的 SessionViewModel；
 * 留在 ViewModel 里就只能在接定时执行时复制一份
 *
 * 保活由本类拉而不是交给调用方：app 进程一死，特权进程的看门狗就自杀并释放虚拟屏
 * （成因见 [com.aliothmoon.maafw.service.RunForegroundService]）。它是执行的一部分，
 * 漏在任何一条发起路径上，表现都是「任务跑一半自己停了」
 */
class RunLauncher(
    private val projectRepository: ProjectRepository,
    private val configurationStore: UserConfigurationStore,
    private val runnerPort: RunnerPort,
    private val keepAlive: RunKeepAlive,
) {

    suspend fun launch(): RunLaunchResult {
        val project = projectRepository.state.value
        if (project !is ProjectState.Ready) return RunLaunchResult.ProjectNotReady

        val config = configurationStore.data.first()
        return when (val built = RunPlanBuilder.build(project.definition, config)) {
            RunPlanResult.NoExecutableTasks -> RunLaunchResult.NoExecutableTasks
            is RunPlanResult.Invalid -> RunLaunchResult.Invalid(built.diagnostics)
            is RunPlanResult.Success -> when (val command = runnerPort.start(built.plan)) {
                // 受理之后才拉保活：前台服务 onCreate 时读 RunnerState 判去留，
                // 提前拉会撞上「还没进 Preparing」而当场自停
                RunnerCommandResult.Accepted -> {
                    keepAlive.start()
                    RunLaunchResult.Started
                }

                is RunnerCommandResult.Rejected -> RunLaunchResult.Rejected(command.reason)
            }
        }
    }
}
