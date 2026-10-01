package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import com.aliothmoon.maafw.i18n.UiText
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
        clock = { now ?: System.currentTimeMillis() },
    )

    private var includeDetails = false

    /** 默认跟真实时钟走（会话文件名按开始时间取）；只有要错开 agent 行的用例才接管，见 [emitAgentLines] */
    private var now: Long? = null

    /**
     * 每行隔开一秒投一条 agent 输出，躲开合成器的洪泛滑窗（2s 内 15 行）
     *
     * 认不出的回调合成器直接丢掉，能进原始档的只剩 agent 输出，要攒满一窗只能这样喂
     */
    private fun emitAgentLines(runner: RecordingEventRunnerPort, count: Int, prefix: String = "agent") {
        repeat(count) {
            now = (now ?: System.currentTimeMillis()) + 1_000
            runner.emit(RunnerEvent.AgentOutput("$prefix $it", fromStderr = false))
        }
    }

    /**
     * 屏上那份是攒批发布的，读 `runLog.value` 之前先把那一拍走完
     *
     * 不能用 advanceUntilIdle：攒批循环是 backgroundScope 里的 `while (true)`，
     * 那个 API 有意不驱动后台工作，否则它自己就永远返回不了
     */
    private fun TestScope.settleRunLog() = testScheduler.advanceTimeBy(SETTLE_MILLIS)

    private fun sessionRecords(): List<RunSessionRecord> {
        val file = File(logDir, "run").listFiles()?.single() ?: return emptyList()
        return records(file)
    }

    private fun sessionRecordsByFirstTask(): Map<String, List<RunSessionRecord>> =
        File(logDir, "run").listFiles().orEmpty().map(::records).associateBy {
            (it.first() as RunSessionRecord.Header).tasks.first()
        }

    private fun records(file: File): List<RunSessionRecord> = file.readLines()
        .filter { it.isNotBlank() }
        .map { LENIENT.decodeFromString(RunSessionRecord.serializer(), it) }

    private fun List<RunSessionRecord>.lineTexts() = filterIsInstance<RunSessionRecord.Line>().map { it.text }

    private suspend fun RunLogRecorder.finish(runner: RecordingEventRunnerPort, executionId: String = ID) {
        runner.emit(RunnerEvent.ExecutionFinished(ExecutionResult.Completed(emptyList())), executionId)
        end(executionId, RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
    }

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

    /** 状态句进的是通知栏，那里不渲染 Markdown 与 HTML；屏上那份留原文交给 MaaMarkdown */
    @Test
    fun `focus markup is stripped from the notification status but kept in the log`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)
        val content = """<span style="color: red">体力不足</span>，**已停止**"""

        runner.emit(
            RunnerEvent.Focus(
                FocusMessage(
                    message = "Node.PipelineNode.Succeeded",
                    content = content,
                    channels = setOf(FocusChannel.Log),
                    trace = false,
                ),
            ),
        )
        settleRunLog()

        assertEquals("体力不足，已停止", recorder.liveUpdateStatus.value)
        assertEquals("体力不足，已停止", recorder.lastUserFacing.value)
        assertEquals(UiText.Verbatim(content), recorder.runLog.value.progress.single().text)
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
    fun `raw callbacks do not become lastUserFacing`() = runTest(dispatcher) {
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
        recorder.begin(planOf("清体力"), ID)
        assertNull(recorder.lastUserFacing.value)
        assertNull(recorder.liveUpdateStatus.value)
        recorder.finish(runner)
    }

    @Test
    fun `events outside a session stay in memory`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("还没开工"))

        assertEquals(1, recorder.runLog.value.all.size)
        // 没开会话就不该凭空造出一个文件
        assertNull(File(logDir, "run").listFiles()?.firstOrNull())
    }

    @Test
    fun `journal notes land in memory and the session file`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("清体力"), ID)
        recorder.warn(ID, com.aliothmoon.maafw.i18n.uiTextFromFramework("内存偏紧"))
        recorder.finish(runner)

        val line = recorder.runLog.value.all.single()
        assertEquals(RunLogKind.Warning, line.kind)
        assertEquals(
            "内存偏紧",
            (sessionRecords().filterIsInstance<RunSessionRecord.Line>().single().text),
        )
    }

    @Test
    fun `a session writes header lines and footer`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("清体力", "签到"), ID)
        runner.emit(RunnerEvent.Log("跑起来了"))
        recorder.finish(runner)

        val records = sessionRecords()
        assertEquals(listOf("清体力", "签到"), (records.first() as RunSessionRecord.Header).tasks)
        assertEquals("跑起来了", (records[1] as RunSessionRecord.Line).text)
        assertEquals(
            RunSessionOutcome.COMPLETED,
            (records.last() as RunSessionRecord.Footer).outcome,
        )
    }

    /** 没投出去也要留一份：「昨晚为什么没跑」是查这份日志的头号问题 */
    @Test
    fun `a round that never dispatched still gets a footer`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("清体力"), ID)
        recorder.end(ID, RunEndReason.NotRun(NotRunCause.Rejected))

        assertEquals(
            RunSessionOutcome.NOT_RUN,
            (sessionRecords().last() as RunSessionRecord.Footer).outcome,
        )
    }

    /** 认不出的回调调试模式也不留，屏上与文件都没有：maa.log 里连 details 都有全份 */
    @Test
    fun `raw callbacks are neither shown nor written`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("a"), ID)
        runner.emit(RunnerEvent.Log("开始"))
        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"name":"A"}"""))
        includeDetails = true
        // 换个事件名：合成器按 kind + 正文去重，同名的第二条本来就到不了落盘这步
        runner.emit(RunnerEvent.Callback("Node.Recognition.Failed", """{"name":"B"}"""))
        recorder.finish(runner)

        assertEquals(listOf("开始"), sessionRecords().lineTexts())

        settleRunLog()
        assertEquals(listOf(UiText.Verbatim("开始")), recorder.runLog.value.all.map { it.text })
    }

    /** 合成过的行照常落盘；details_json 占掉文件的绝大部分体积，只有调试模式才带 */
    @Test
    fun `composed details only reach the file in debug mode`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("a"), ID)
        runner.emit(RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"A"}"""))
        includeDetails = true
        // 换一种合成行：同 kind 同正文的第二条会被合成器去重掉
        runner.emit(RunnerEvent.Callback("Resource.Loading.Failed", """{"path":"B"}"""))
        recorder.finish(runner)

        val lines = sessionRecords().filterIsInstance<RunSessionRecord.Line>()
        assertEquals(listOf(RunLogKind.Error, RunLogKind.Error), lines.map { it.kind })
        assertNull(lines[0].detail)
        assertEquals("""{"path":"B"}""", lines[1].detail)
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

        recorder.begin(planOf("a"), "e1")
        runner.emit(RunnerEvent.Log("同一句"), "e1")
        recorder.finish(runner, "e1")

        recorder.begin(planOf("a"), "e2")
        runner.emit(RunnerEvent.Log("同一句"), "e2")
        recorder.finish(runner, "e2")
        settleRunLog()

        // 屏上已换成这一轮；被去重掉的话这里是空的
        assertEquals(listOf(UiText.Verbatim("同一句")), recorder.runLog.value.all.map { it.text })
    }

    /** 屏上只留这一轮：几轮叠在一起分不清哪句是哪轮的，上一轮的在会话文件里 */
    @Test
    fun `a new session starts the on-screen log afresh`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("上一轮"), "e1")
        runner.emit(RunnerEvent.Log("上一轮的话"), "e1")
        recorder.finish(runner, "e1")
        settleRunLog()
        assertTrue(recorder.runLog.value.all.isNotEmpty())

        recorder.begin(planOf("这一轮"), "e2")
        settleRunLog()
        assertEquals(RunLogSnapshot.EMPTY, recorder.runLog.value)

        runner.emit(RunnerEvent.Log("这一轮的话"), "e2")
        recorder.finish(runner, "e2")
        settleRunLog()
        assertEquals(listOf(UiText.Verbatim("这一轮的话")), recorder.runLog.value.all.map { it.text })
        // 历史不动：上一轮的仍在它自己的文件里
        assertEquals(listOf("上一轮的话"), sessionRecordsByFirstTask().getValue("上一轮").lineTexts())
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
        backgroundScope.launch { recorder.runLog.collect { sizes += it.all.size } }
        settleRunLog()

        repeat(20) { index -> runner.emit(RunnerEvent.Log("line $index")) }
        settleRunLog()

        assertEquals(20, sizes.last())
        // 不钉死次数：攒批的节拍怎么排是实现的事，逐条发布才是要拦的那件事
        assertTrue("逐条发布了，共 ${sizes.size} 次", sizes.size <= 3)
    }

    /** 回归：进度行与原始行曾共用一个窗口，原始行一刷，「进度」档更早的行就被挤没了 */
    @Test
    fun `a raw line flood does not evict progress lines`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        repeat(10) { runner.emit(RunnerEvent.Log("progress $it")) }
        // 文本各不相同：合成器会去掉连续重复的行
        emitAgentLines(runner, RUN_LOG_CAPACITY + 100)
        settleRunLog()

        val snapshot = recorder.runLog.value
        assertEquals((0 until 10).map { UiText.Verbatim("progress $it") }, snapshot.progress.map { it.text })
        // 「全部」档：进度行一条不丢，原始行只留最新的一窗
        assertEquals(10 + RUN_LOG_CAPACITY, snapshot.all.size)
        assertEquals(snapshot.progress, snapshot.all.filter { it.isEssential })
        assertEquals(UiText.Verbatim("agent 100"), snapshot.all.first { !it.isEssential }.text)
        assertEquals(snapshot.all.sortedBy { it.id }, snapshot.all)
        // 省略提示插在最老一条保留下来的原始行前面
        assertEquals(100L, snapshot.omittedRaw)
        assertEquals(snapshot.all.first { !it.isEssential }.id, snapshot.firstRawId)
    }

    @Test
    fun `progress lines are capped on their own`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        repeat(RUN_LOG_CAPACITY + 5) { runner.emit(RunnerEvent.Log("line $it")) }
        settleRunLog()

        val snapshot = recorder.runLog.value
        assertEquals(RUN_LOG_CAPACITY, snapshot.progress.size)
        assertEquals(UiText.Verbatim("line 5"), snapshot.progress.first().text)
        // 丢的是进度行，不算进原始行的省略数
        assertEquals(0L, snapshot.omittedRaw)
        assertNull(snapshot.firstRawId)
    }

    @Test
    fun `clearing resets both tiers and the omission count`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(RunnerEvent.Log("progress"))
        emitAgentLines(runner, RUN_LOG_CAPACITY + 1)
        settleRunLog()
        recorder.clear()
        emitAgentLines(runner, 1, prefix = "after")
        settleRunLog()

        val snapshot = recorder.runLog.value
        assertTrue(snapshot.progress.isEmpty())
        assertEquals(listOf(UiText.Verbatim("after 0")), snapshot.all.map { it.text })
        assertEquals(0L, snapshot.omittedRaw)
    }

    @Test
    fun `end waits for the terminal marker before writing the footer`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("a"), ID)
        val ending = async { recorder.end(ID, RunEndReason.Ran(ExecutionResult.Completed(emptyList()))) }
        runner.emit(RunnerEvent.Log("收尾前才消费到的一行"))
        runner.emit(RunnerEvent.ExecutionFinished(ExecutionResult.Completed(emptyList())))
        ending.await()

        val records = sessionRecords()
        assertEquals(listOf("收尾前才消费到的一行"), records.lineTexts())
        assertTrue(records.last() is RunSessionRecord.Footer)
    }

    @Test
    fun `a late event of the previous run stays in its own file`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        recorder.begin(planOf("上一轮"), "e1")
        val endingFirst = async { recorder.end("e1", RunEndReason.Ran(ExecutionResult.Completed(emptyList()))) }
        recorder.begin(planOf("这一轮"), "e2")
        runner.emit(RunnerEvent.Log("上一轮的尾巴"), "e1")
        assertNull(recorder.lastUserFacing.value)
        runner.emit(RunnerEvent.ExecutionFinished(ExecutionResult.Completed(emptyList())), "e1")
        endingFirst.await()
        runner.emit(RunnerEvent.Log("这一轮的第一句"), "e2")
        recorder.finish(runner, "e2")

        val files = sessionRecordsByFirstTask()
        assertEquals(listOf("上一轮的尾巴"), files.getValue("上一轮").lineTexts())
        assertEquals(listOf("这一轮的第一句"), files.getValue("这一轮").lineTexts())
    }

    @Test
    fun `task lines use the label frozen in the envelope`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        val recorder = recorder(runner)

        runner.emit(
            RunnerEvent.Callback("Tasker.Task.Failed", """{"entry":"Start"}"""),
            taskLabel = "启动",
        )
        settleRunLog()

        val text = recorder.runLog.value.all.single().text as com.aliothmoon.maafw.i18n.UiText.Resource
        assertEquals(listOf("启动"), text.args)
    }

    private fun planOf(vararg taskNames: String) = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition(name = "official", paths = listOf("resource"), label = "官服"),
        runConfigurationId = RunConfigurationId("cfg"),
        tasks = taskNames.map { RuntimeTask(taskName = it, entry = it, pipelineOverrides = emptyList()) },
    )

    private companion object {
        /** FocusDispatcher 只拿它查 $i18n；本用例的 focus 不走翻译，空项目就够 */
        val DEFINITION = ProjectDefinition(
            name = "demo",
            version = "1",
            controllers = listOf(ControllerDefinition()),
            resources = emptyList(),
            tasks = emptyList(),
            groups = emptyList(),
            options = emptyMap(),
            templates = emptyList(),
        )
        val LENIENT = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        const val ID = RecordingEventRunnerPort.DEFAULT_EXECUTION_ID

        /** 宽出 RunLogRecorder.FLUSH_INTERVAL_MS 一截，那个常量是私有的，不为测试开出来 */
        const val SETTLE_MILLIS = 500L
    }
}
