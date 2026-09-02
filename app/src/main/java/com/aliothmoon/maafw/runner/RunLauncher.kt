package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

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

    /** 点名要跑的那份运行配置已经被删了 */
    data object ConfigurationMissing : RunLaunchResult
    data class Invalid(val diagnostics: List<Diagnostic>) : RunLaunchResult

    /** RunnerPort 拒绝原因；UiText 随 locale 解析（外壳自产走 resId，底层原文走 uiTextFromFramework） */
    data class Rejected(val reason: UiText) : RunLaunchResult

    /** 被某道 [RunPrecheck] 拦下，或 gating 挂载物挂了 */
    data class Blocked(val reason: UiText) : RunLaunchResult

    /** 同一个 [RunRequestId] 已经处理过；不是失败，是这次请求本就该被丢掉 */
    data object DuplicateRequest : RunLaunchResult

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
    private val journal: RunJournal,
) {

    /** 只护投递这一段，不护整轮；运行中的第二次 Start 由 RunnerPort 拒 */
    private val gate = Mutex()

    /** 已受理过的请求 id；只留最近若干条，闹钟重投的间隔是秒级，不需要长记忆 */
    private val handled = ArrayDeque<RunRequestId>()

    /** 抢占时要撤掉上一轮的收尾登记，否则旧的自动熄屏会落到新一轮头上 */
    private val settling = AtomicReference<Job?>(null)

    /**
     * @param configurationId null 跑当前激活的那份；定时规则可以指定别的
     * @param requestId 非 null 时做幂等：同一个 id 第二次进来直接 [RunLaunchResult.DuplicateRequest]
     * @param force 已有执行在跑时是否掐掉它再上；false 就让 RunnerPort 拒
     * @param steps 非 null 时把每个挂载物的落点抄一份给调用方，用于记账
     * @param signals 用户的打断面；只有会等待的挂载物（倒计时）读它
     * @param progress 挂载物的进度上报口；调用方决定往哪显示
     */
    suspend fun launch(
        trigger: RunTrigger,
        acknowledged: Set<ConfirmToken> = emptySet(),
        configurationId: RunConfigurationId? = null,
        requestId: RunRequestId? = null,
        force: Boolean = false,
        steps: RunStepSink? = null,
        signals: RunSignals = RunSignals(),
        progress: RunProgress = RunProgress { _, _ -> },
    ): RunLaunchResult {
        if (!gate.tryLock()) {
            return RunLaunchResult.Blocked(uiTextOf(R.string.msg_launch_in_progress))
        }
        val engaged = ArrayDeque<Release>()
        try {
            if (requestId != null && requestId in handled) {
                Timber.i("duplicate run request, skipping: %s", requestId.value)
                return RunLaunchResult.DuplicateRequest
            }
            val project = projectRepository.state.value
            if (project !is ProjectState.Ready) return RunLaunchResult.ProjectNotReady

            val config = configurationStore.data.first()
            // 指定了但已被删：与「配置存在但没有可执行任务」分开报，
            // 否则用户看到「没有可用的任务」会去翻一个根本不存在的配置
            if (configurationId != null && config.configuration(configurationId) == null) {
                return RunLaunchResult.ConfigurationMissing
            }
            val built = RunPlanBuilder.build(
                project.definition,
                config,
                configurationId,
                clientVersion = BuildConfig.VERSION_NAME,
                clientLanguage = AppLocales.currentProjectTag(),
            )
            val plan = when (built) {
                RunPlanResult.NoExecutableTasks -> return RunLaunchResult.NoExecutableTasks
                is RunPlanResult.Invalid -> return RunLaunchResult.Invalid(built.diagnostics)
                is RunPlanResult.Success -> built.plan
            }

            val ctx = RunContext(
                executionId = java.util.UUID.randomUUID().toString(),
                trigger = trigger,
                runMode = runMode(),
                plan = plan,
                acknowledged = acknowledged,
                signals = signals,
                progress = progress,
                journal = journal,
            )
            runPrechecks(ctx)?.let { return it }

            if (force) preemptRunning()

            engage(Anchor.BeforeDispatch, ctx, engaged, steps)?.let { halt ->
                finalize(engaged, halt.cause)
                return RunLaunchResult.Blocked(halt.reason)
            }

            when (val command = runnerPort.start(plan, ctx.executionId)) {
                RunnerCommandResult.Accepted -> Unit
                is RunnerCommandResult.Rejected -> {
                    finalize(engaged, RunEndReason.NotRun(NotRunCause.Rejected))
                    return RunLaunchResult.Rejected(command.reason)
                }
            }
            requestId?.let(::remember)

            engage(Anchor.AfterAccepted, ctx, engaged, steps)?.let { halt ->
                finalize(engaged, halt.cause)
                return RunLaunchResult.Blocked(halt.reason)
            }

            // 交棒给守着整轮的协程。此刻 phase 一定是 busy——两个 RunnerPort 实现都在
            // 返回 Accepted 之前就置了 Preparing，屏障不会当场看到 Idle 就退
            val pending = engaged.toList()
            engaged.clear()
            settling.set(scope.launch { awaitSettledThenFinalize(pending) })
            return RunLaunchResult.Started
        } catch (cancellation: CancellationException) {
            finalize(engaged, RunEndReason.NotRun(NotRunCause.Cancelled))
            throw cancellation
        } finally {
            gate.unlock()
        }
    }

    private fun remember(requestId: RunRequestId) {
        handled.addLast(requestId)
        while (handled.size > HANDLED_HISTORY) handled.removeFirst()
    }

    /**
     * 掐掉在跑的那一轮并等它停稳，然后把它的收尾跑完
     *
     * 顺序不能换：先 stop 让 Runner 收敛，再 join 上一轮的收尾协程——反过来会让新一轮的
     * engage 与旧一轮的 release 交错，屏保刚盖上就被上一轮撤掉
     */
    private suspend fun preemptRunning() {
        if (!runnerPort.state.value.phase.isBusy) return
        Timber.i("preempt: aborting the running round")
        runnerPort.stop()
        settling.getAndSet(null)?.join()
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

    /** 非 null = gating 失败，调用方撤栈并 [RunLaunchResult.Blocked] */
    private suspend fun engage(
        anchor: Anchor,
        ctx: RunContext,
        engaged: ArrayDeque<Release>,
        steps: RunStepSink?,
    ): Halt? {
        for (hook in hooks.asSequence().filter { it.anchor == anchor }.sortedBy { it.order }) {
            // Accepted 之后再拦会留下远端在跑、编排报 Blocked 的孤儿轮
            check(!(anchor == Anchor.AfterAccepted && hook.gating)) {
                "gating hook ${hook.id} cannot use AfterAccepted"
            }
            val result = try {
                withTimeout(ENGAGE_TIMEOUT_MS) { hook.engage(ctx) }
            } catch (timeout: TimeoutCancellationException) {
                EngageResult.Failed(uiTextOf(R.string.msg_hook_timeout, hook.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Timber.w(t, "env hook %s engage crashed", hook.id)
                EngageResult.Failed(uiTextOf(R.string.msg_hook_failed, hook.id))
            }
            val release = when (result) {
                is EngageResult.Engaged -> result.release
                is EngageResult.Skipped -> result.release
                is EngageResult.Failed -> null
            }
            val outcome = when (result) {
                is EngageResult.Engaged -> HookOutcome.ENGAGED
                is EngageResult.Skipped ->
                    if (release != null) HookOutcome.ENGAGED else HookOutcome.SKIPPED
                is EngageResult.Failed -> HookOutcome.FAILED
            }
            steps?.record(RunStep(hook.id, outcome))
            if (release != null) engaged.addLast(release)
            if (result is EngageResult.Failed && hook.gating) {
                return Halt(result.reason, RunEndReason.NotRun(result.notRun))
            }
        }
        return null
    }

    private class Halt(val reason: UiText, val cause: RunEndReason)

    /**
     * 等待任务结束直到
     */
    private suspend fun awaitSettledThenFinalize(pending: List<Release>) {
        val settled = runnerPort.state.first { !it.phase.isBusy }
        val result = settled.latestResult ?: run {
            Timber.e("runner became idle without a result, synthesizing Failed")
            ExecutionResult.Failed(uiTextOf(R.string.msg_fail_default))
        }
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
                    Timber.w(t, "release failed, continuing with the rest")
                }
            }
        }
    }

    private companion object {
        const val ENGAGE_TIMEOUT_MS = 30_000L
        const val RELEASE_TIMEOUT_MS = 10_000L

        /** 闹钟重投的间隔是秒级，记这么多足够，也不至于无限长 */
        const val HANDLED_HISTORY = 32
    }
}
