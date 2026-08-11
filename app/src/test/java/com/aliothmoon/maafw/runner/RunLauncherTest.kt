package com.aliothmoon.maafw.runner

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
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        controller = ControllerDefinition(),
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
    ) = RunLauncher(
        projectRepository = FakeProjectRepository(project),
        configurationStore = configurationStore,
        runnerPort = runner,
        prechecks = prechecks,
        hooks = hooks,
        runMode = { runMode },
        scope = scope,
    )

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
            prechecks = listOf(RunPrecheck { Verdict.Block(uiTextOf(R.string.runner_foreground_blocked)) }),
            hooks = listOf(hook),
        )

        val result = launcher.launch(RunTrigger.Manual)

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue((result as RunLaunchResult.Blocked).reason.isResource(R.string.runner_foreground_blocked))
        // 检查在改环境之前，挂载物一个都不该跑
        assertEquals(emptyList<String>(), hook.log)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
    }

    @Test
    fun `foreground mode precheck blocks and background passes`() = runTest(testDispatcher) {
        suspend fun launchIn(mode: RunMode) = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(ForegroundModePrecheck),
            runMode = mode,
        ).launch(RunTrigger.Manual)

        assertTrue(launchIn(RunMode.FOREGROUND) is RunLaunchResult.Blocked)
        assertEquals(RunLaunchResult.Started, launchIn(RunMode.BACKGROUND))
    }

    /** 确认循环：先问，带着 token 重跑就该放行 */
    @Test
    fun `confirmation is asked once and the re-run passes`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        val check = RunPrecheck { ctx ->
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
                RunPrecheck { Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks)) },
            ),
        )

        val result = launcher.launch(RunTrigger.Schedule("s1"))

        assertTrue(result is RunLaunchResult.Blocked)
        assertTrue((result as RunLaunchResult.Blocked).reason.isResource(R.string.msg_no_executable_tasks))
    }

    /** 检查忘了消费自己的 token 就会无限弹框；守卫把它挡成一次明确失败 */
    @Test
    fun `precheck that ignores its own token is blocked instead of looping`() = runTest(testDispatcher) {
        val token = ConfirmToken("demo")
        val launcher = launcher(
            scope = backgroundScope,
            runner = fastStub(backgroundScope),
            prechecks = listOf(
                RunPrecheck { Verdict.NeedsConfirmation(token, uiTextOf(R.string.msg_no_executable_tasks)) },
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
        assertEquals(RunEndReason.NotRun(NotRunCause.Rejected), hook.releaseReason)
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
        assertEquals(RunEndReason.NotRun(NotRunCause.HookFailed), ok.releaseReason)
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
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
            override suspend fun engage(ctx: RunContext) = Release { error("撤不掉") }
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

    /** 等不到就强撤，否则屏保永远盖着 */
    @Test
    fun `settle timeout still releases`() = runTest(testDispatcher) {
        val hook = RecordingHook("env", Anchor.BeforeDispatch)
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 10 * 60_000, taskDelayMillis = 0),
        )
        val launcher = launcher(scope = backgroundScope, runner = runner, hooks = listOf(hook))

        launcher.launch(RunTrigger.Manual)
        advanceTimeBy(31_000)

        val reason = hook.releaseReason
        assertTrue(reason is RunEndReason.Ran)
        assertTrue((reason as RunEndReason.Ran).result is ExecutionResult.Failed)
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

        override suspend fun engage(ctx: RunContext): Release {
            failWith?.let { throw it }
            log += "engage:$id"
            return Release { reason ->
                releaseReason = reason
                log += "release:$id"
            }
        }
    }
}
