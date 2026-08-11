package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 执行期间把 app 进程钉住的手段
 *
 * 抽成接口只为让挂载物不必认识 Context 与 Service；
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

    /** RunnerPort 拒绝；原文来自 Runner，不翻译 */
    data class Rejected(val reason: String) : RunLaunchResult

    /** 被某道 [RunPrecheck] 拦下，或 gating 挂载物挂了 */
    data class Blocked(val reason: UiText) : RunLaunchResult

    /**
     * 要用户点头。调用方弹框，用户同意后带上 token 重新 [RunLauncher.launch]
     *
     * 定时触发不会拿到这个——没人可问，编排层已把它降级成 [Blocked]
     */
    data class NeedsConfirmation(val token: ConfirmToken, val prompt: UiText) : RunLaunchResult
}

/**
 * 发起一轮执行的编排：检查 → 改环境 → 投递 → 受理后补挂 → 等结束 → 逆序收尾
 *
 * 落在进程级而不是 ViewModel 级：这几步没有一步需要 UI 状态，而定时触发落在 Service 里，
 * 那里既没有 ViewModelStoreOwner 也拿不到 Activity 作用域的 SessionViewModel
 *
 * 阶段的划分依据见 docs/runner-execution.md §5
 */
class RunLauncher(
    private val projectRepository: ProjectRepository,
    private val configurationStore: UserConfigurationStore,
    private val runnerPort: RunnerPort,
    /** 有序；顺序即判定顺序，短路在第一个非 Pass 上 */
    private val prechecks: List<RunPrecheck>,
    private val hooks: List<RunEnvHook>,
    /** 每轮现读，不缓存：用户可能在两轮之间改了运行模式 */
    private val runMode: () -> RunMode,
    /** 收尾要守着整轮，活得比 launch 的调用方久 */
    private val scope: CoroutineScope,
) {

    /** 只护投递这一段，不护整轮；运行中的第二次 Start 由 RunnerPort 拒 */
    private val gate = Mutex()

    suspend fun launch(
        trigger: RunTrigger,
        acknowledged: Set<ConfirmToken> = emptySet(),
    ): RunLaunchResult {
        if (!gate.tryLock()) {
            return RunLaunchResult.Blocked(uiTextOf(R.string.msg_launch_in_progress))
        }
        val engaged = ArrayDeque<Release>()
        try {
            val project = projectRepository.state.value
            if (project !is ProjectState.Ready) return RunLaunchResult.ProjectNotReady

            val config = configurationStore.data.first()
            val plan = when (val built = RunPlanBuilder.build(project.definition, config)) {
                RunPlanResult.NoExecutableTasks -> return RunLaunchResult.NoExecutableTasks
                is RunPlanResult.Invalid -> return RunLaunchResult.Invalid(built.diagnostics)
                is RunPlanResult.Success -> built.plan
            }

            val ctx = RunContext(trigger, runMode(), plan, acknowledged)
            runPrechecks(ctx)?.let { return it }

            engage(Anchor.BeforeDispatch, ctx, engaged)

            when (val command = runnerPort.start(plan)) {
                RunnerCommandResult.Accepted -> Unit
                is RunnerCommandResult.Rejected -> {
                    finalize(engaged, RunEndReason.NotRun(NotRunCause.Rejected))
                    return RunLaunchResult.Rejected(command.reason)
                }
            }

            engage(Anchor.AfterAccepted, ctx, engaged)

            // 交棒给守着整轮的协程。此刻 phase 一定是 busy——两个 RunnerPort 实现都在
            // 返回 Accepted 之前就置了 Preparing，屏障不会当场看到 Idle 就退
            val pending = engaged.toList()
            engaged.clear()
            scope.launch { awaitSettledThenFinalize(pending) }
            return RunLaunchResult.Started
        } catch (failure: GatingHookFailure) {
            finalize(engaged, RunEndReason.NotRun(NotRunCause.HookFailed))
            return RunLaunchResult.Blocked(failure.reason)
        } catch (cancellation: CancellationException) {
            finalize(engaged, RunEndReason.NotRun(NotRunCause.Cancelled))
            throw cancellation
        } finally {
            gate.unlock()
        }
    }

    /**
     * 返回非 null 即整套没过。顺序敏感：短路在第一个非 Pass 上，
     * 后面的检查可能依赖前面的前提，收齐了一起报会问出无意义的问题
     */
    private suspend fun runPrechecks(ctx: RunContext): RunLaunchResult? {
        for (check in prechecks) {
            when (val verdict = check.evaluate(ctx)) {
                Verdict.Pass -> Unit

                is Verdict.Block -> return RunLaunchResult.Blocked(verdict.reason)

                is Verdict.NeedsConfirmation -> return when {
                    // 检查没认出自己上一轮问过的 token，再放行就是死循环弹框。
                    // 把编程错误挡成一次明确失败，而不是让用户点到手软
                    verdict.token in ctx.acknowledged ->
                        RunLaunchResult.Blocked(uiTextOf(R.string.msg_precheck_ignored_confirmation))

                    // 降级统一在这里，不在检查里：放进检查的话每加一道都得记得降级，
                    // 忘一次就在定时触发时弹出没人能点的框
                    ctx.trigger is RunTrigger.Schedule ->
                        RunLaunchResult.Blocked(verdict.prompt)

                    else -> RunLaunchResult.NeedsConfirmation(verdict.token, verdict.prompt)
                }
            }
        }
        return null
    }

    private suspend fun engage(anchor: Anchor, ctx: RunContext, engaged: ArrayDeque<Release>) {
        hooks.asSequence()
            .filter { it.anchor == anchor }
            .sortedBy { it.order }
            .forEach { hook ->
                val release = try {
                    withTimeout(ENGAGE_TIMEOUT_MS) { hook.engage(ctx) }
                } catch (timeout: TimeoutCancellationException) {
                    onEngageFailure(hook, timeout, uiTextOf(R.string.msg_hook_timeout, hook.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    onEngageFailure(hook, t, uiTextOf(R.string.msg_hook_failed, hook.id))
                }
                if (release != null) engaged.addLast(release)
            }
    }

    /** gating 的抛出去中止整轮，其余记一笔继续——静音没静上不该拦住整晚的任务 */
    private fun onEngageFailure(hook: RunEnvHook, cause: Throwable, reason: UiText): Release? {
        if (hook.gating) throw GatingHookFailure(reason, cause)
        Timber.w(cause, "环境挂载物 %s engage 失败，跳过", hook.id)
        return null
    }

    private suspend fun awaitSettledThenFinalize(pending: List<Release>) {
        // Fw 的 stop 是耗时操作：最坏要等当前节点跑完，而 PI 单节点 timeout 默认 20s。
        // 等 phase 真的落回非 busy 再撤，否则屏保会在任务还没停的时候被掀掉
        val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            runnerPort.state.first { !it.phase.isBusy }
        }
        if (settled == null) Timber.w("等运行结束超时，强行收尾")
        val result = (settled ?: runnerPort.state.value).latestResult
            ?: ExecutionResult.Failed("等待运行结束超时")
        finalize(ArrayDeque(pending), RunEndReason.Ran(result))
    }

    /** 逆序、不可取消、逐项隔离：一项挂了或卡了，后面照撤 */
    private suspend fun finalize(engaged: ArrayDeque<Release>, reason: RunEndReason) {
        if (engaged.isEmpty()) return
        withContext(NonCancellable) {
            while (engaged.isNotEmpty()) {
                val release = engaged.removeLast()
                try {
                    withTimeout(RELEASE_TIMEOUT_MS) { release(reason) }
                } catch (t: Throwable) {
                    Timber.w(t, "收尾失败，继续撤后面的")
                }
            }
        }
    }

    private class GatingHookFailure(val reason: UiText, cause: Throwable) : Exception(cause)

    private companion object {
        const val ENGAGE_TIMEOUT_MS = 30_000L
        const val RELEASE_TIMEOUT_MS = 10_000L

        /** PI 单节点 timeout 默认 20s，stop 最坏等一个节点跑完，留一档余量 */
        const val SETTLE_TIMEOUT_MS = 30_000L
    }
}
