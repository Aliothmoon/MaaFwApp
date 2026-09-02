package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** 落盘的边界在这里验；合成规则归 RunLogComposerTest */
@OptIn(ExperimentalCoroutinesApi::class)
class RunLogRecorderTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var logDir: File

    @Before
    fun setUp() {
        logDir = createTempDirectory("recorder").toFile()
        // 落点与 IO 线程都是进程级固定值，RunSessionLogStore 内部直读；不打桩会写到真 LOG_DIR，
        // 且 runTest 等不到 Dispatchers.IO 上的写盘，读文件的断言会变成偶发失败
        mockkObject(AppPaths)
        every { AppPaths.LOG_DIR } returns logDir
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        unmockkObject(AppPaths)
        unmockkObject(MaaDispatchers)
        logDir.deleteRecursively()
    }

    private fun TestScope.recorder(runner: RunnerPort): RunLogRecorder = RunLogRecorder(
        runnerPort = runner,
        focusDispatcher = FocusDispatcher(
            projectRepository = FakeProjectRepository(ProjectState.Ready(DEFINITION, emptyList())),
            resolver = PassthroughFocusContentResolver,
            runnerPort = runner,
            scope = backgroundScope,
        ),
        store = RunSessionLogStore(),
        // 生产是查资源；这里只要能看出「落盘的是渲染后的字符串」
        renderText = { text -> (text as? com.aliothmoon.maafw.i18n.UiText.Verbatim)?.value ?: "<res>" },
        includeDetails = { includeDetails },
        scope = backgroundScope,
    )

    private var includeDetails = false

    /**
     * 屏上那份是攒批发布的，读 `runLog.value` 之前先把那一拍走完
     *
     * 不能用 advanceUntilIdle：攒批循环是 backgroundScope 里的 `while (true)`，
     * 那个 API 有意不驱动后台工作，否则它自己就永远返回不了
     */
    private fun TestScope.settleRunLog() = testScheduler.advanceTimeBy(SETTLE_MILLIS)

    private fun sessionRecords(): List<RunSessionRecord> {
        val file = File(logDir, "run").listFiles()?.maxByOrNull { it.lastModified() } ?: return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { LENIENT.decodeFromString(RunSessionRecord.serializer(), it) }
    }

    private fun sessionRecordsFor(taskName: String): List<RunSessionRecord> =
        File(logDir, "run").listFiles().orEmpty()
            .map { file ->
                file.readLines()
                    .filter { it.isNotBlank() }
                    .map { LENIENT.decodeFromString(RunSessionRecord.serializer(), it) }
            }
            .single { records -> (records.first() as? RunSessionRecord.Header)?.tasks == listOf(taskName) }

    @Test
    fun `user-facing lines update lastUserFacing immediately`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        assertNull(recorder.lastUserFacing.value)
        runner.emit(RunnerEvent.Log("跑起来了"))
        assertEquals("跑起来了", recorder.lastUserFacing.value)
    }

    @Test
    fun `pipeline node becomes liveUpdateStatus when there is no focus`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("任务开始"))
        runner.emit(
            RunnerEvent.Callback(
                "Node.PipelineNode.Starting",
                """{"name":"StartFight"}""",
            ),
        )
        assertEquals("任务开始", recorder.lastUserFacing.value)
        assertEquals("StartFight", recorder.liveUpdateStatus.value)
    }

    @Test
    fun `focus wins over the pipeline node for liveUpdateStatus`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(
            RunnerEvent.Callback(
                "Node.PipelineNode.Starting",
                """{"name":"StartFight"}""",
            ),
        )
        runner.emit(
            RunnerEvent.Focus(
                FocusMessage(
                    message = "Node.PipelineNode.Succeeded",
                    content = "刷到第3关",
                    channels = setOf(FocusChannel.Log),
                    trace = false,
                ),
            ),
        )
        assertEquals("刷到第3关", recorder.liveUpdateStatus.value)
    }

    @Test
    fun `a new PI task clears the pipeline node status`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(
            RunnerEvent.Callback(
                "Node.PipelineNode.Starting",
                """{"name":"StartFight"}""",
            ),
        )
        assertEquals("StartFight", recorder.liveUpdateStatus.value)
        runner.emit(RunnerEvent.Progress("下一任务", 1, 2))
        assertEquals("下一任务 1/2", recorder.lastUserFacing.value)
        assertNull(recorder.liveUpdateStatus.value)
    }

    @Test
    fun `clearing the on-screen log does not wipe liveUpdateStatus`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(
            RunnerEvent.Callback(
                "Node.PipelineNode.Starting",
                """{"name":"StartFight"}""",
            ),
        )
        assertEquals("StartFight", recorder.liveUpdateStatus.value)
        recorder.clear()
        assertEquals("StartFight", recorder.liveUpdateStatus.value)
    }

    @Test
    fun `clearing the log resets composer dedupe through recorder input`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"清体力"}"""))
        settleRunLog()
        assertEquals(1, recorder.runLog.value.size)
        recorder.clear()
        testScheduler.runCurrent()
        runner.emit(RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"清体力"}"""))
        settleRunLog()

        assertEquals("clear 后同一条被去重丢了", 1, recorder.runLog.value.size)
    }

    @Test
    fun `verbose callbacks do not become lastUserFacing`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("关键"))
        runner.emit(RunnerEvent.Callback("Node.Action.Starting", """{"name":"A"}"""))
        assertEquals("关键", recorder.lastUserFacing.value)
    }

    @Test
    fun `a new session clears lastUserFacing`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("上一轮"))
        recorder.beginSession(planOf("清体力"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
        assertNull(recorder.lastUserFacing.value)
        assertNull(recorder.liveUpdateStatus.value)
        recorder.endSession(
            "execution-1",
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )
    }

    @Test
    fun `events outside a session stay in memory`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("还没开工"))

        assertEquals(1, recorder.runLog.value.size)
        // 没开会话就不该凭空造出一个文件
        assertNull(File(logDir, "run").listFiles()?.firstOrNull())
    }

    @Test
    fun `journal notes land in memory and the session file`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("清体力"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
        recorder.warn(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            com.aliothmoon.maafw.i18n.uiTextFromFramework("内存偏紧"),
        )
        recorder.end(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        val line = recorder.runLog.value.single()
        assertEquals(RunLogKind.Warning, line.kind)
        assertEquals(
            "内存偏紧",
            (sessionRecords().filterIsInstance<RunSessionRecord.Line>().single().text),
        )
    }

    @Test
    fun `old finalization and notes cannot take over the new session`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("旧任务"), "execution-1")
        recorder.beginSession(planOf("新任务"), "execution-2")
        runner.emit(RunnerEvent.Log("新执行状态"), executionId = "execution-2")
        recorder.warn("execution-1", UiText.Verbatim("旧执行警告"))
        settleRunLog()

        assertEquals("新执行状态", recorder.lastUserFacing.value)
        assertTrue(recorder.runLog.value.none { it.text == UiText.Verbatim("旧执行警告") })
        recorder.end("execution-1", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        runner.emit(RunnerEvent.Log("新执行仍在"), executionId = "execution-2")
        recorder.end("execution-2", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        val records = sessionRecordsFor("新任务")
        assertEquals(
            listOf("新任务"),
            (records.first() as RunSessionRecord.Header).tasks,
        )
        val lines = records.filterIsInstance<RunSessionRecord.Line>().map { it.text }
        assertEquals(listOf("新执行状态", "新执行仍在"), lines)
        assertEquals(
            RunSessionOutcome.COMPLETED,
            (records.last() as RunSessionRecord.Footer).outcome,
        )
    }

    @Test
    fun `a session writes header lines and footer`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("清体力", "签到"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
        runner.emit(RunnerEvent.Log("跑起来了"))
        recorder.endSession(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        val records = sessionRecords()
        assertEquals(listOf("清体力", "签到"), (records.first() as RunSessionRecord.Header).tasks)
        assertEquals("跑起来了", (records[1] as RunSessionRecord.Line).text)
        assertEquals(
            RunSessionOutcome.COMPLETED,
            (records.last() as RunSessionRecord.Footer).outcome,
        )
    }

    @Test
    fun `endAfterDrain waits for a buffered failure before closing the file`() =
        runTest(dispatcher) {
            val recordingRunner = RecordingEventRunnerPort()
            val runner = GatedRecordingRunnerPort(recordingRunner)
            val recorder = recorder(runner)

            recorder.beginSession(planOf("清体力"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
            runner.hold()
            runner.emit(
                RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"清体力"}"""),
                executionId = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            )
            runner.emit(
                RunnerEvent.ExecutionFinished,
                executionId = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            )
            val ending = async {
                recorder.endAfterDrain(
                    RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
                    RunEndReason.Ran(ExecutionResult.CompletedWithFailures(emptyList())),
                )
            }

            assertTrue(ending.isActive)
            runner.release()
            advanceUntilIdle()
            ending.await()

            val records = sessionRecords()
            assertEquals(
                RunLogKind.Error,
                records.filterIsInstance<RunSessionRecord.Line>().single().kind,
            )
            assertTrue(records.last() is RunSessionRecord.Footer)
        }

    @Test
    fun `a new begin does not skip the old drain failing log`() =
        runTest(dispatcher) {
            val recordingRunner = RecordingEventRunnerPort()
            val runner = GatedRecordingRunnerPort(recordingRunner)
            val recorder = recorder(runner)

            recorder.beginSession(planOf("旧任务"), "execution-1")
            runner.hold()
            runner.emit(
                RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"旧任务"}"""),
                executionId = "execution-1",
            )
            runner.emit(
                RunnerEvent.ExecutionFinished,
                executionId = "execution-1",
            )
            val oldEnding = async {
                recorder.endAfterDrain(
                    "execution-1",
                    RunEndReason.Ran(ExecutionResult.CompletedWithFailures(emptyList())),
                )
            }

            assertTrue(oldEnding.isActive)
            recorder.beginSession(planOf("新任务"), "execution-2")
            runner.release()
            advanceUntilIdle()
            oldEnding.await()
            recorder.endSession(
                "execution-2",
                RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
            )

            val records = sessionRecordsFor("旧任务")
            assertEquals(
                RunLogKind.Error,
                records.filterIsInstance<RunSessionRecord.Line>().single().kind,
            )
            assertEquals(
                RunSessionOutcome.COMPLETED_WITH_FAILURES,
                (records.last() as RunSessionRecord.Footer).outcome,
            )
        }

    /** 没投出去也要留一份：「昨晚为什么没跑」是查这份日志的头号问题 */
    @Test
    fun `a round that never dispatched still gets a footer`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("清体力"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
        recorder.endSession(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            RunEndReason.NotRun(NotRunCause.Rejected),
        )

        assertEquals(
            RunSessionOutcome.NOT_RUN,
            (sessionRecords().last() as RunSessionRecord.Footer).outcome,
        )
    }

    /** details_json 占掉文件的绝大部分体积，只有调试模式才值得带 */
    @Test
    fun `raw details only reach the file in debug mode`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("a"), RecordingEventRunnerPort.DEFAULT_EXECUTION_ID)
        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"name":"A"}"""))
        includeDetails = true
        // 换个事件名：合成器按 kind + 正文去重，同名的第二条本来就到不了落盘这步
        runner.emit(RunnerEvent.Callback("Node.Recognition.Failed", """{"name":"B"}"""))
        recorder.endSession(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )

        val lines = sessionRecords().filterIsInstance<RunSessionRecord.Line>()
        assertNull(lines[0].detail)
        assertEquals("""{"name":"B"}""", lines[1].detail)
    }

    /**
     * 开新一轮要重置合成器
     *
     * 去重靠的是「与上一条一模一样就丢掉」，跨轮留着的话新一轮的第一条会被上一轮的末条吃掉
     */
    @Test
    fun `a new session does not dedup against the previous one`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("a"), "execution-1")
        runner.emit(RunnerEvent.Log("同一句"), executionId = "execution-1")
        recorder.endSession("execution-1", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        settleRunLog()
        val before = recorder.runLog.value.size
        recorder.beginSession(planOf("a"), "execution-2")
        runner.emit(RunnerEvent.Log("同一句"), executionId = "execution-2")
        recorder.endSession("execution-2", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        settleRunLog()

        assertTrue("跨轮被去重掉了", recorder.runLog.value.size > before)
    }

    @Test
    fun `task entry labels do not borrow another task name`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(
            plan = planOf(
                RuntimeTask("A", "Fight", emptyList(), label = "第一任务"),
                RuntimeTask("Fight", "Other", emptyList(), label = "第二任务"),
            ),
            executionId = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
        )
        runner.emit(RunnerEvent.Callback("Tasker.Task.Succeeded", """{"entry":"Fight"}"""))
        recorder.endSession(
            RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
            RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
        )
        settleRunLog()

        assertEquals(
            UiText.Resource(
                R.string.run_log_task_succeeded,
                listOf("第一任务"),
            ),
            recorder.runLog.value.single().text,
        )
    }

    @Test
    fun `a callback consumed after session end keeps its frozen label`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(
            planOf(RuntimeTask("A", "Fight", emptyList(), label = "战斗")),
            executionId = "execution-1",
        )
        recorder.endSession("execution-1", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        runner.emit(
            RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"Fight"}"""),
            executionId = "execution-1",
        )
        settleRunLog()

        assertEquals(
            UiText.Resource(
                R.string.run_log_task_failed,
                listOf("战斗"),
            ),
            recorder.runLog.value.single().text,
        )
    }

    @Test
    fun `a stale essential event after both sessions end cannot replace final status`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(planOf("旧任务"), "execution-1")
        recorder.endSession("execution-1", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        recorder.beginSession(planOf("新任务"), "execution-2")
        runner.emit(RunnerEvent.Log("新执行失败"), executionId = "execution-2")
        recorder.endSession("execution-2", RunEndReason.Ran(ExecutionResult.Failed(
            UiText.Verbatim("failed"),
        )))

        assertEquals("新执行失败", recorder.lastUserFacing.value)
        runner.emit(
            RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"旧任务"}"""),
            executionId = "execution-1",
        )
        settleRunLog()

        assertEquals("新执行失败", recorder.lastUserFacing.value)
    }

    @Test
    fun `a stale essential event after a superseded session ends cannot replace final status`() =
        runTest(dispatcher) {
            val runner = RecordingEventRunnerPort()
            val recorder = recorder(runner)

            recorder.beginSession(planOf("旧任务"), "execution-1")
            recorder.beginSession(planOf("新任务"), "execution-2")
            runner.emit(RunnerEvent.Log("新执行状态"), executionId = "execution-2")
            recorder.endSession("execution-2", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

            assertEquals("新执行状态", recorder.lastUserFacing.value)
            runner.emit(
                RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"旧任务"}"""),
                executionId = "execution-1",
            )
            settleRunLog()

            assertEquals("新执行状态", recorder.lastUserFacing.value)
        }

    @Test
    fun `an old callback after the next begin is dropped from ui and file`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(
            plan = planOf(RuntimeTask("A", "Fight", emptyList(), label = "旧任务")),
            executionId = "execution-1",
        )
        recorder.endSession("execution-1", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        recorder.beginSession(
            plan = planOf(RuntimeTask("B", "Other", emptyList(), label = "新任务")),
            executionId = "execution-2",
        )
        runner.emit(
            RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"Fight"}"""),
            executionId = "execution-1",
        )
        recorder.endSession("execution-2", RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        settleRunLog()

        assertTrue(recorder.runLog.value.isEmpty())
        assertNull(recorder.lastUserFacing.value)
        val lines = sessionRecords().filterIsInstance<RunSessionRecord.Line>()
        assertEquals(emptyList<RunSessionRecord.Line>(), lines)
    }

    @Test
    fun `an evicted execution cannot reclaim the idle ui`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        repeat(9) { index ->
            val executionId = "execution-$index"
            recorder.beginSession(planOf("任务$index"), executionId)
            recorder.endSession(
                executionId,
                RunEndReason.Ran(ExecutionResult.Completed(emptyList())),
            )
        }
        runner.emit(
            RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"任务0"}"""),
            executionId = "execution-0",
        )
        settleRunLog()

        assertNull(recorder.lastUserFacing.value)
        assertTrue(recorder.runLog.value.isEmpty())
    }

    @Test
    fun `an entry shared by differently named tasks stays unambiguous`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.beginSession(
            plan = planOf(
                RuntimeTask("A", "Fight", emptyList(), label = "第一任务"),
                RuntimeTask("B", "Fight", emptyList(), label = "第二任务"),
            ),
            executionId = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
        )
        runner.emit(RunnerEvent.Callback("Tasker.Task.Starting", """{"entry":"Fight"}"""))
        settleRunLog()

        assertEquals(
            UiText.Resource(
                R.string.run_log_task_starting,
                listOf("Fight"),
            ),
            recorder.runLog.value.single().text,
        )
    }

    /**
     * 一串突发只出几次新列表，不是一条一次
     *
     * 逐条发布要按条复制整份 [RUN_LOG_CAPACITY] 列表，还让 UI 跟着事件率重组；
     * 识别期一秒几十条，这条回归掉了不会有任何测试变红，只会变卡
     */
    @Test
    fun `a burst of entries publishes as a few batches`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        val sizes = mutableListOf<Int>()
        backgroundScope.launch { recorder.runLog.collect { sizes += it.size } }
        settleRunLog()

        repeat(20) { index -> runner.emit(RunnerEvent.Log("line $index")) }
        settleRunLog()

        assertEquals(20, sizes.last())
        // 不钉死次数：攒批的节拍怎么排是实现的事，逐条发布才是要拦的那件事
        assertTrue("逐条发布了，共 ${sizes.size} 次", sizes.size <= 3)
    }

    private fun planOf(vararg taskNames: String) = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition(name = "official", paths = listOf("resource"), label = "官服"),
        runConfigurationId = RunConfigurationId("cfg"),
        tasks = taskNames.map { RuntimeTask(taskName = it, entry = it, pipelineOverrides = emptyList()) },
    )

    private fun planOf(vararg tasks: RuntimeTask) = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition(name = "official", paths = listOf("resource"), label = "官服"),
        runConfigurationId = RunConfigurationId("cfg"),
        tasks = tasks.toList(),
    )

    private class GatedRecordingRunnerPort(
        private val runner: RecordingEventRunnerPort,
    ) : RunnerPort by runner {
        @Volatile
        private var gate = CompletableDeferred<Unit>()

        override val events = runner.events.map { envelope ->
            gate.await()
            envelope
        }

        fun hold() {
            gate = CompletableDeferred()
        }

        fun release() {
            gate.complete(Unit)
        }

        fun emit(
            event: RunnerEvent,
            executionId: String = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID,
        ) = runner.emit(event, executionId)
    }

    private companion object {
        /** FocusDispatcher 只拿它查 $i18n；本用例的 focus 不走翻译，空项目就够 */
        val DEFINITION = ProjectDefinition(
            name = "demo",
            version = "1",
            controller = ControllerDefinition(),
            resources = emptyList(),
            tasks = emptyList(),
            groups = emptyList(),
            options = emptyMap(),
            templates = emptyList(),
        )
        val LENIENT = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** 宽出 RunLogRecorder.FLUSH_INTERVAL_MS 一截，那个常量是私有的，不为测试开出来 */
        const val SETTLE_MILLIS = 500L
    }
}
