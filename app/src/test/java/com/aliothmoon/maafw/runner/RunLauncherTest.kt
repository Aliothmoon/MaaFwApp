package com.aliothmoon.maafw.runner

import android.util.Log
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class RunLauncherTest {

    /**
     * 必须 Unconfined：[StubRunnerPort] 的 execute 挂在传入 scope 上，
     * StandardTestDispatcher 下它不推进，phase 永远停在 Preparing，屏障就等不到收尾
     */
    private val testDispatcher = UnconfinedTestDispatcher()
    private val emptyJson = JsonObject(emptyMap())

    private val definition = ProjectDefinition(
        name = "demo",
        version = "1",
        controllers = listOf(ControllerDefinition()),
        resources = listOf(ResourceDefinition("官服", listOf("./base"))),
        tasks = listOf(
            TaskDefinition(
                name = "启动游戏",
                entry = "Start",
                label = "启动游戏",
                description = null,
                groups = emptyList(),
                optionNames = emptyList(),
                pipelineOverride = emptyJson,
                controllers = emptyList(),
                resources = emptyList(),
                defaultCheck = true,
            ),
        ),
        groups = listOf(TaskGroupDefinition(name = "ungrouped", isUngrouped = true)),
        options = emptyMap(),
        templates = emptyList(),
    )

    private fun store(tasks: List<ConfiguredTask> = listOf(ConfiguredTask("启动游戏", instanceId = "t1"))) =
        InMemoryUserConfigurationStore(
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(RunConfiguration(RunConfigurationId("c1"), "日常", tasks)),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )

    private fun launcher(
        scope: CoroutineScope,
        runner: RunnerPort,
        prechecks: List<RunPrecheck> = emptyList(),
        hooks: List<RunEnvHook> = emptyList(),
        project: ProjectState = ProjectState.Ready(definition, emptyList()),
        configurationStore: InMemoryUserConfigurationStore = store(),
        runMode: RunMode = RunMode.BACKGROUND,
        journal: RunJournal = DiscardingRunJournal,
    ) = RunLauncher(
        projectRepository = FakeProjectRepository(project),
        configurationStore = configurationStore,
        runnerPort = runner,
        prechecks = prechecks,
        hooks = hooks,
        runMode = { runMode },
        scope = scope,
        journal = journal,
        renderText = { text -> (text as? UiText.Verbatim)?.value ?: "<res>" },
    )

    private fun precheck(id: String = "test", verdict: suspend (RunContext) -> Verdict) = object : RunPrecheck {
        override val id = id
        override suspend fun evaluate(ctx: RunContext) = verdict(ctx)
    }

    private fun fastStub(scope: CoroutineScope) = StubRunnerPort(
        scope = scope,
        scenario = StubRunnerScenario(prepareDelayMillis = 0, taskDelayMillis = 0),
    )

    /**
     * 时延非零，屏障才会真的挂起等一次状态边沿，而不是一进去就看到 Idle
     *
     * 配套用 advanceTimeBy 推进而不是 advanceUntilIdle：实测后者在这套
     * stub + backgroundScope 的组合下推不动虚拟时间，收尾等不到
     */
    private fun slowStub(scope: CoroutineScope) = StubRunnerPort(
        scope = scope,
        scenario = StubRunnerScenario(prepareDelayMillis = 100, taskDelayMillis = 100),
    )

    // ── 保活：既有行为不能因为改成挂载物而变 ──────────────────────────

    @Test
    fun `keep alive engages once the runner accepts`() = runTest(testDispatcher) {
        val keepAlive = RecordingRunKeepAlive()
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            hooks = listOf(KeepAliveHook(keepAlive)),
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        assertEquals(1, keepAlive.startCount)
    }

    @Test
    fun `journal and runner receive the same generated execution id`() = runTest(testDispatcher) {
        val runner = RecordingEventRunnerPort()
        val journal = RecordingRunJournal()
        val launcher = launcher(
            scope = backgroundScope,
            runner = runner,
            hooks = listOf(SessionLogHook(journal)),
            journal = journal,
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))

        val executionId = journal.begins.singleOrNull()?.second
        assertTrue(executionId != null && executionId.isNotBlank())
        assertEquals(listOf(executionId), runner.startedExecutionIds)
    }

    /** 保活是执行的一部分：没受理就拉，前台服务会因为读到非 busy 而当场自停 */
    @Test
    fun `keep alive stays untouched when there is nothing to run`() = runTest(testDispatcher) {
        val keepAlive = RecordingRunKeepAlive()
        val launcher = launcher(
            scope = backgroundScope,
            runner = StubRunnerPort(scope = backgroundScope),
            configurationStore = store(listOf(ConfiguredTask("启动游戏", enabled = false, instanceId = "t1"))),
            hooks = listOf(KeepAliveHook(keepAlive)),
        )

        assertEquals(RunLaunchResult.NoExecutableTasks, launcher.launch(RunTrigger.Manual))
        assertEquals(0, keepAlive.startCount)
    }

    @Test
    fun `project still loading is reported without touching the runner`() = runTest(testDispatcher) {
        val keepAlive = RecordingRunKeepAlive()
        val launcher = launcher(
            scope = backgroundScope,
            runner = StubRunnerPort(scope = backgroundScope),
            project = ProjectState.Loading,
            hooks = listOf(KeepAliveHook(keepAlive)),
        )

        assertEquals(RunLaunchResult.ProjectNotReady, launcher.launch(RunTrigger.Manual))
        assertEquals(0, keepAlive.startCount)
    }

    @Test
    fun `second launch while busy is rejected and does not re-arm keep alive`() = runTest(testDispatcher) {
        val keepAlive = RecordingRunKeepAlive()
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val launcher = launcher(
            scope = backgroundScope,
            runner = runner,
            hooks = listOf(KeepAliveHook(keepAlive)),
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        assertTrue(launcher.launch(RunTrigger.Manual) is RunLaunchResult.Rejected)
        assertEquals(1, keepAlive.startCount)
    }

    // ── 检查 ─────────────────────────────────────────────────────────

    @Test
    fun `blocking precheck stops before the runner is touched`() = runTest(testDispatcher) {
        val runner = fastStub(backgroundScope)
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val launcher = launcher(
            scope = backgroundScope,
            runner = runner,
            prechecks = listOf(precheck { Verdict.Block(uiTextOf(R.string.runner_foreground_blocked)) }),
            hooks = listOf(hook),
        )

        val result = launcher.launch(RunTrigger.Manual)

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue((result as RunLaunchResult.Blocked).reason.isResource(R.string.runner_foreground_blocked))
        assertEquals(BlockSource.Precheck("test"), result.source)
        // 检查在改环境之前，挂载物一个都不该跑
        assertEquals(emptyList<String>(), hook.log)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
    }

    @Test
    fun `foreground mode blocks schedule trigger but passes manual`() = runTest(testDispatcher) {
        suspend fun launchIn(mode: RunMode, trigger: RunTrigger = RunTrigger.Manual) = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(ForegroundModePrecheck),
            runMode = mode,
        ).launch(trigger)

        // 悬浮窗手动开跑是前台模式的正路
        assertEquals(RunLaunchResult.Started, launchIn(RunMode.FOREGROUND))
        assertEquals(RunLaunchResult.Started, launchIn(RunMode.BACKGROUND))
        val blocked = launchIn(RunMode.FOREGROUND, RunTrigger.Schedule("s1")) as RunLaunchResult.Blocked
        assertTrue(blocked.reason.isResource(R.string.runner_foreground_schedule_blocked))
        assertEquals(BlockSource.Precheck(ForegroundModePrecheck.ID), blocked.source)
    }

    /** 确认循环：先问，带着 token 重跑就该放行 */
    @Test
    fun `confirmation is asked once and the re-run passes`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        val check = precheck { ctx ->
            if (token in ctx.acknowledged) Verdict.Pass
            else Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks))
        }
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(check),
        )

        val first = launcher.launch(RunTrigger.Manual)
        assertTrue(first is RunLaunchResult.NeedsConfirmation)
        assertEquals(token, (first as RunLaunchResult.NeedsConfirmation).token)

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual, setOf(token)))
    }

    /** 定时触发没人可问，降级成拦截而不是弹一个没人能点的框 */
    @Test
    fun `confirmation degrades to blocked for scheduled triggers`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(
                precheck { Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks)) },
            ),
        )

        val result = launcher.launch(RunTrigger.Schedule("s1"))

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue((result as RunLaunchResult.Blocked).reason.isResource(R.string.msg_no_executable_tasks))
        // 降级来的拦截也要认得出是哪一道，定时记账靠它分档
        assertEquals(BlockSource.Precheck("test"), result.source)

        // 悬浮窗同样没地方弹框
        assertTrue(launcher.launch(RunTrigger.Overlay) is RunLaunchResult.Blocked)
    }

    /** 提醒类没人可问时照跑：拦下的话定时任务会因为一个护眼开关整轮不跑 */
    @Test
    fun `advisory confirmation passes when nobody can answer`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        fun launcherWithAdvisory() = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(
                precheck {
                    Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks), advisory = true)
                },
            ),
        )

        assertEquals(RunLaunchResult.Started, launcherWithAdvisory().launch(RunTrigger.Schedule("s1")))
        assertEquals(RunLaunchResult.Started, launcherWithAdvisory().launch(RunTrigger.Overlay))
        assertTrue(launcherWithAdvisory().launch(RunTrigger.Manual) is RunLaunchResult.NeedsConfirmation)
    }

    /** 检查忘了消费自己的 token 就会无限弹框；守卫把它挡成一次明确失败 */
    @Test
    fun `precheck that ignores its own token is blocked instead of looping`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(
                precheck { Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks)) },
            ),
        )

        val result = launcher.launch(RunTrigger.Manual, setOf(token))

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue(
            (result as RunLaunchResult.Blocked).reason
                .isResource(R.string.msg_precheck_ignored_confirmation),
        )
    }

    // ── 挂载物 ───────────────────────────────────────────────────────

    @Test
    fun `releases run in reverse engage order after the run settles`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val first = RecordingHook("first", Anchor.BeforeDispatch, order = 0, log = log)
        val second = RecordingHook("second", Anchor.BeforeDispatch, order = 1, log = log)
        val late = RecordingHook("late", Anchor.AfterAccepted, log = log)
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(late, second, first), // 声明顺序故意打乱，排序应看 anchor + order
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        advanceTimeBy(1_000)

        assertEquals(
            listOf(
                "engage:first", "engage:second", "engage:late",
                "release:late", "release:second", "release:first",
            ),
            log,
        )
    }

    @Test
    fun `release carries the runner outcome`() = runTest(testDispatcher) {
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(hook),
        )

        launcher.launch(RunTrigger.Manual)
        advanceTimeBy(1_000)

        val reason = hook.releaseReason
        assertTrue(reason is RunEndReason.Ran)
        assertTrue((reason as RunEndReason.Ran).result is ExecutionResult.Completed)
    }

    /** 投递被拒时 runner 从没跑过，但环境已经改了，必须当场撤 */
    @Test
    fun `rejected dispatch releases what was already engaged`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val hook = RecordingHook("env", Anchor.BeforeDispatch, log = log)
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val launcher = launcher(scope = backgroundScope, runner = runner, hooks = listOf(hook))

        launcher.launch(RunTrigger.Manual)
        log.clear()
        hook.releaseReason = null

        val result = launcher.launch(RunTrigger.Manual)

        assertTrue(result is RunLaunchResult.Rejected)
        assertEquals(listOf("engage:env", "release:env"), log)
        // 收尾拿到的原因与调用方拿到的同一句：运行日志靠它写出是哪一步被拒
        assertEquals(
            RunEndReason.NotRun(NotRunCause.Rejected, (result as RunLaunchResult.Rejected).reason),
            hook.releaseReason,
        )
    }

    @Test
    fun `gating hook failure aborts and releases the earlier ones`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val ok = RecordingHook("ok", Anchor.BeforeDispatch, order = 0, log = log)
        val bad = RecordingHook(
            "bad", Anchor.BeforeDispatch, order = 1, gating = true, log = log,
            failWith = IllegalStateException("解锁失败"),
        )
        val runner = fastStub(backgroundScope)
        val launcher = launcher(
            scope = backgroundScope,
            runner = runner,
            hooks = listOf(ok, bad),
        )

        val result = launcher.launch(RunTrigger.Manual)

        assertTrue(result is RunLaunchResult.Blocked)
        assertEquals(listOf("engage:ok", "release:ok"), log)
        val notRun = ok.releaseReason as RunEndReason.NotRun
        assertEquals(NotRunCause.HookFailed, notRun.cause)
        assertTrue(notRun.reason!!.isResource(R.string.msg_hook_failed))
        // engage 抛出的异常一路带到收尾与调用方，堆栈才进得了日志
        assertEquals("解锁失败", notRun.error?.message)
        assertEquals("解锁失败", (result as RunLaunchResult.Blocked).error?.message)
        assertEquals(BlockSource.Hook("bad", NotRunCause.HookFailed), result.source)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
    }

    @Test
    fun `a full scheduled countdown is not cut off by the engage timeout`() = runTest(testDispatcher) {
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            hooks = listOf(CountdownHook),
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Schedule("s1")))
    }

    @Test
    fun `a failed gating result is recorded then blocks`() = runTest(testDispatcher) {
        val steps = mutableListOf<RunStep>()
        val hook = object : RunEnvHook {
            override val id = "countdown"
            override val anchor = Anchor.BeforeDispatch
            override val order = 0
            override val gating = true
            override suspend fun engage(ctx: RunContext) =
                EngageResult.Failed(
                    uiTextOf(R.string.run_countdown_cancelled),
                    NotRunCause.Cancelled,
                )
        }
        val recorder = RecordingHook("env", Anchor.BeforeDispatch, order = -1)
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            hooks = listOf(recorder, hook),
        )

        val result = launcher.launch(RunTrigger.Manual, steps = RunStepSink { steps += it })

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue((result as RunLaunchResult.Blocked).reason.isResource(R.string.run_countdown_cancelled))
        // 用户取消与真失败分开：前者不该发「没跑起来」的通知
        assertEquals(BlockSource.Hook("countdown", NotRunCause.Cancelled), result.source)
        assertEquals(
            RunEndReason.NotRun(NotRunCause.Cancelled, uiTextOf(R.string.run_countdown_cancelled)),
            recorder.releaseReason,
        )
        assertEquals(
            listOf(
                RunStep("env", HookOutcome.ENGAGED),
                RunStep("countdown", HookOutcome.FAILED),
            ),
            steps,
        )
    }

    @Test
    fun `a gating hook after accept is rejected as a programming error`() = runTest(testDispatcher) {
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            hooks = listOf(
                RecordingHook("late", Anchor.AfterAccepted, gating = true),
            ),
        )

        val thrown = runCatching { launcher.launch(RunTrigger.Manual) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    /** 静音没静上不该拦住整晚的任务 */
    @Test
    fun `non gating hook failure is skipped and the run proceeds`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val bad = RecordingHook(
            "bad", Anchor.BeforeDispatch, order = 0, gating = false, log = log,
            failWith = IllegalStateException("静音失败"),
        )
        val ok = RecordingHook("ok", Anchor.BeforeDispatch, order = 1, log = log)
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(bad, ok),
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        advanceTimeBy(1_000)

        // 挂掉的那个没入栈，收尾里也不该出现
        assertEquals(listOf("engage:ok", "release:ok"), log)
        assertNull(bad.releaseReason)
    }

    /** 一项收尾挂了不能让后面的不撤 */
    @Test
    fun `a failing release does not stop the rest`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val outer = RecordingHook("outer", Anchor.BeforeDispatch, order = 0, log = log)
        val exploding = object : RunEnvHook {
            override val id = "boom"
            override val anchor = Anchor.BeforeDispatch
            override val order = 1
            override val gating = false
            override suspend fun engage(ctx: RunContext) =
                EngageResult.Engaged(Release { error("撤不掉") })
        }
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(outer, exploding),
        )

        launcher.launch(RunTrigger.Manual)
        advanceTimeBy(1_000)

        assertEquals(listOf("engage:outer", "release:outer"), log)
    }

    // ── 指定运行配置（定时规则用） ────────────────────────────────────

    /** 定时规则可以绑一份不是当前激活的配置 */
    @Test
    fun `an explicit configuration id runs that one instead of the active one`() = runTest(testDispatcher) {
        val other = RunConfigurationId("c2")
        val store = InMemoryUserConfigurationStore(
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(
                    RunConfiguration(RunConfigurationId("c1"), "日常", emptyList()),
                    RunConfiguration(other, "周常", listOf(ConfiguredTask("启动游戏", instanceId = "t1"))),
                ),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        // slowStub：跑得慢一点，activeExecution 才还在，能直接读到编译进 plan 的那份 id
        val runner = slowStub(backgroundScope)
        val launcher = launcher(scope = backgroundScope, runner = runner, configurationStore = store)

        // 激活的那份没有任务，点名的那份有——结果能区分开是哪一份被编译了
        assertEquals(RunLaunchResult.NoExecutableTasks, launcher.launch(RunTrigger.Manual))
        assertEquals(
            RunLaunchResult.Started,
            launcher.launch(RunTrigger.Schedule("s1"), configurationId = other),
        )
        assertEquals(other, runner.state.value.activeExecution?.runConfigurationId)
    }

    /** 绑定的配置被删了要单独报，不能混进「没有可用的任务」 */
    @Test
    fun `a deleted target configuration is reported on its own`() = runTest(testDispatcher) {
        val launcher = launcher(scope = backgroundScope, runner = fastStub(backgroundScope))

        val result = launcher.launch(
            RunTrigger.Schedule("s1"),
            configurationId = RunConfigurationId("gone"),
        )

        assertEquals(RunLaunchResult.ConfigurationMissing, result)
    }

    // ── 屏障 ─────────────────────────────────────────────────────────

    /** Fw 的 stop 是耗时操作：跑着的时候把屏保掀掉，用户会看到任务还在跑但屏幕亮了 */
    @Test
    fun `release does not run while the runner is still busy`() = runTest(testDispatcher) {
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(hook),
        )

        launcher.launch(RunTrigger.Manual)
        advanceTimeBy(50)

        assertNull(hook.releaseReason)
    }

    /** 自然长跑没有墙钟：phase 还 busy 就不能撤 */
    @Test
    fun `a long running round is not torn down while still busy`() = runTest(testDispatcher) {
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 0, taskDelayMillis = 60_000),
        )
        val launcher = launcher(scope = backgroundScope, runner = runner, hooks = listOf(hook))

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        advanceTimeBy(31_000)

        assertNull(hook.releaseReason)
        assertTrue(runner.state.value.phase.isBusy)
    }

    /** Stop 只把 phase 打成 Stopping，收尾仍等 !isBusy，不能靠墙钟提前掀屏保 */
    @Test
    fun `stopping does not release until the runner is idle`() = runTest(testDispatcher) {
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 10 * 60_000, taskDelayMillis = 0),
        )
        val launcher = launcher(scope = backgroundScope, runner = runner, hooks = listOf(hook))

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        runner.stop()
        advanceTimeBy(31_000)

        assertNull(hook.releaseReason)
        assertEquals(RunnerPhase.Stopping, runner.state.value.phase)
    }

    /** 界面上的提示一闪就没了，没跑起来的原因与异常都要留在 app.log */
    @Test
    fun `a blocked round is logged with its reason and stack`() = runTest(testDispatcher) {
        val logs = RecordingTree()
        Timber.plant(logs)
        try {
            val bad = RecordingHook(
                "bad", Anchor.BeforeDispatch, gating = true,
                failWith = IllegalStateException("解锁失败"),
            )
            val launcher = launcher(scope = backgroundScope, runner = fastStub(backgroundScope), hooks = listOf(bad))

            launcher.launch(RunTrigger.Schedule("s1"))

            // Timber 把堆栈拼在正文后面，堆栈里又有本用例的方法名，只能按开头认
            val entry = logs.entries.single { it.priority == Log.WARN && it.message.startsWith("run ") }
            assertTrue(entry.message, entry.message.startsWith("run schedule:s1 (active) blocked by Hook(id=bad, cause=HookFailed): "))
            assertEquals("解锁失败", entry.error?.message)
        } finally {
            Timber.uproot(logs)
        }
    }

    @Test
    fun `a started round is not logged as a failure`() = runTest(testDispatcher) {
        val logs = RecordingTree()
        Timber.plant(logs)
        try {
            val launcher = launcher(scope = backgroundScope, runner = fastStub(backgroundScope))

            assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
            assertTrue(logs.entries.none { it.priority >= Log.WARN })
        } finally {
            Timber.uproot(logs)
        }
    }

    private class RecordingTree : Timber.Tree() {
        data class Entry(val priority: Int, val message: String, val error: Throwable?)

        val entries = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, message, t)
        }
    }

    // ── 排队 ─────────────────────────────────────────────────────────

    /** 两条定时同一刻到：后到的先等前一发投递完，再等那一轮连收尾都走完 */
    @Test
    fun `a queued launch waits for the earlier round to dispatch, run and release`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val slowEngage = object : RunEnvHook {
            override val id = "slow"
            override val anchor = Anchor.BeforeDispatch
            override val order = 0
            override val gating = false
            override suspend fun engage(ctx: RunContext): EngageResult {
                log += "engage"
                delay(500)
                return EngageResult.Engaged(Release { log += "release" })
            }
        }
        val waits = mutableListOf<String>()
        val launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            hooks = listOf(slowEngage),
        )
        fun queued(strategyId: String) = async {
            launcher.launch(
                RunTrigger.Schedule(strategyId),
                whenBusy = WhenBusy.Queue(60_000),
                progress = RunProgress { hookId, _ -> waits += "$strategyId:$hookId" },
            )
        }

        val first = queued("s1")
        val second = queued("s2")
        advanceTimeBy(2_000)

        assertEquals(RunLaunchResult.Started, first.await())
        assertEquals(RunLaunchResult.Started, second.await())
        assertEquals(listOf("engage", "release", "engage", "release"), log)
        assertEquals(listOf("s2:${WhenBusy.Queue.PROGRESS_ID}"), waits)
    }

    @Test
    fun `a rejecting launch during another dispatch is blocked as busy`() = runTest(testDispatcher) {
        val slowEngage = object : RunEnvHook {
            override val id = "slow"
            override val anchor = Anchor.BeforeDispatch
            override val order = 0
            override val gating = false
            override suspend fun engage(ctx: RunContext): EngageResult {
                delay(500)
                return EngageResult.Skipped()
            }
        }
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            hooks = listOf(slowEngage),
        )

        val first = async { launcher.launch(RunTrigger.Manual) }
        val second = launcher.launch(RunTrigger.Overlay)
        advanceTimeBy(1_000)

        assertEquals(BlockSource.Busy, (second as RunLaunchResult.Blocked).source)
        assertEquals(RunLaunchResult.Started, first.await())
    }

    /** 上一轮已落回 Idle、收尾还在跑：当场 engage 会被它的收尾掐掉，要等它收完，且算作有人在等 */
    @Test
    fun `a launch right after a round waits for that round's release`() = runTest(testDispatcher) {
        for (whenBusy in listOf(WhenBusy.Reject, WhenBusy.Preempt)) {
            val log = mutableListOf<String>()
            var waitersSeenInRelease: Boolean? = null
            lateinit var launcher: RunLauncher
            val slowRelease = object : RunEnvHook {
                override val id = "slow-release"
                override val anchor = Anchor.BeforeDispatch
                override val order = 0
                override val gating = false
                override suspend fun engage(ctx: RunContext): EngageResult {
                    log += "engage"
                    return EngageResult.Engaged(Release {
                        delay(2_000)
                        if (waitersSeenInRelease == null) waitersSeenInRelease = launcher.hasWaiters
                        log += "release"
                    })
                }
            }
            launcher = launcher(scope = backgroundScope, runner = slowStub(backgroundScope), hooks = listOf(slowRelease))

            assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
            // 跑完只要两三百毫秒，此刻已 Idle，收尾还卡在 delay 里
            advanceTimeBy(1_000)
            val second = async { launcher.launch(RunTrigger.Manual, whenBusy = whenBusy) }
            advanceTimeBy(6_000)

            assertEquals(whenBusy.toString(), RunLaunchResult.Started, second.await())
            assertEquals(whenBusy.toString(), listOf("engage", "release", "engage", "release"), log)
            assertEquals(whenBusy.toString(), true, waitersSeenInRelease)
        }
    }

    /** 进门时上一轮还在跑、检查期间才停下：挂载前仍要等它收尾，检查那段也要算作有人在等 */
    @Test
    fun `a round that stops during prechecks is still waited out`() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        var waitersSeenInRelease: Boolean? = null
        var slowCheck = false
        lateinit var launcher: RunLauncher
        val slowRelease = object : RunEnvHook {
            override val id = "slow-release"
            override val anchor = Anchor.BeforeDispatch
            override val order = 0
            override val gating = false
            override suspend fun engage(ctx: RunContext): EngageResult {
                log += "engage"
                return EngageResult.Engaged(Release {
                    delay(2_000)
                    if (waitersSeenInRelease == null) waitersSeenInRelease = launcher.hasWaiters
                    log += "release"
                })
            }
        }
        launcher = launcher(
            scope = backgroundScope,
            runner = slowStub(backgroundScope),
            prechecks = listOf(precheck { if (slowCheck) delay(1_000); Verdict.Pass }),
            hooks = listOf(slowRelease),
        )

        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        slowCheck = true
        // 此刻上一轮还在跑；它在本轮检查的那一秒里停下并开始收尾
        val second = async { launcher.launch(RunTrigger.Manual) }
        advanceTimeBy(8_000)

        assertEquals(RunLaunchResult.Started, second.await())
        assertEquals(listOf("engage", "release", "engage", "release"), log)
        assertEquals(true, waitersSeenInRelease)
    }

    /** 重新解压持着闸门：当场起跑的报正在启动，排队的定时等它解完才投 */
    @Test
    fun `launches wait out a whenIdle block`() = runTest(testDispatcher) {
        val launcher = launcher(scope = backgroundScope, runner = fastStub(backgroundScope))
        val log = mutableListOf<String>()

        val reinstall = async {
            launcher.whenIdle {
                log += "unpack start"
                delay(1_000)
                log += "unpack end"
            }
        }
        advanceTimeBy(100)
        val rejected = launcher.launch(RunTrigger.Overlay)
        val queued = async {
            launcher.launch(RunTrigger.Schedule("s1"), whenBusy = WhenBusy.Queue(60_000)).also { log += "queued started" }
        }
        advanceTimeBy(2_000)
        reinstall.await()

        assertEquals(BlockSource.Busy, (rejected as RunLaunchResult.Blocked).source)
        assertEquals(RunLaunchResult.Started, queued.await())
        assertEquals(listOf("unpack start", "unpack end", "queued started"), log)
    }

    @Test
    fun `a queued launch gives up once its window passes`() = runTest(testDispatcher) {
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val launcher = launcher(scope = backgroundScope, runner = runner)
        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))

        val queued = async {
            launcher.launch(RunTrigger.Schedule("s1"), whenBusy = WhenBusy.Queue(1_000))
        }
        advanceTimeBy(2_000)

        assertEquals(RunLaunchResult.QueueTimedOut, queued.await())
    }

    private class RecordingHook(
        override val id: String,
        override val anchor: Anchor,
        override val order: Int = 0,
        override val gating: Boolean = false,
        val log: MutableList<String> = mutableListOf(),
        private val failWith: Throwable? = null,
    ) : RunEnvHook {

        var releaseReason: RunEndReason? = null

        override suspend fun engage(ctx: RunContext): EngageResult {
            failWith?.let { throw it }
            log += "engage:$id"
            return EngageResult.Engaged(Release { reason ->
                releaseReason = reason
                log += "release:$id"
            })
        }
    }

    private class RecordingRunJournal : RunJournal {
        val begins = mutableListOf<Pair<RunPlan, String>>()

        override suspend fun begin(plan: RunPlan, executionId: String) {
            begins += plan to executionId
        }

        override suspend fun end(executionId: String, reason: RunEndReason) = Unit

        override fun note(executionId: String, level: RunNote, text: UiText) = Unit
    }
}
