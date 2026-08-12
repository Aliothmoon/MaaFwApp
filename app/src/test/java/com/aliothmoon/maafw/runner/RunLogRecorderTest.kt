package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ProjectDefinition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    }

    @After
    fun tearDown() {
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
        store = RunSessionLogStore(logDir = { logDir }, ioDispatcher = dispatcher),
        // 生产是查资源；这里只要能看出「落盘的是渲染后的字符串」
        renderText = { text -> (text as? com.aliothmoon.maafw.i18n.UiText.Verbatim)?.value ?: "<res>" },
        includeDetails = { includeDetails },
        scope = backgroundScope,
        ioDispatcher = dispatcher,
    )

    private var includeDetails = false

    private fun sessionRecords(): List<RunSessionRecord> {
        val file = File(logDir, "run").listFiles()?.single() ?: return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { LENIENT.decodeFromString(RunSessionRecord.serializer(), it) }
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

        recorder.begin(planOf("清体力"))
        recorder.warn(com.aliothmoon.maafw.i18n.uiTextFromFramework("内存偏紧"))
        recorder.end(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        val line = recorder.runLog.value.single()
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

        recorder.beginSession(planOf("清体力", "签到"))
        runner.emit(RunnerEvent.Log("跑起来了"))
        recorder.endSession(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

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

        recorder.beginSession(planOf("清体力"))
        recorder.endSession(RunEndReason.NotRun(NotRunCause.Rejected))

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

        recorder.beginSession(planOf("a"))
        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"name":"A"}"""))
        includeDetails = true
        // 换个事件名：合成器按 kind + 正文去重，同名的第二条本来就到不了落盘这步
        runner.emit(RunnerEvent.Callback("Node.Recognition.Failed", """{"name":"B"}"""))
        recorder.endSession(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

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

        recorder.beginSession(planOf("a"))
        runner.emit(RunnerEvent.Log("同一句"))
        recorder.endSession(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        val before = recorder.runLog.value.size
        recorder.beginSession(planOf("a"))
        runner.emit(RunnerEvent.Log("同一句"))
        recorder.endSession(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))

        assertTrue("跨轮被去重掉了", recorder.runLog.value.size > before)
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
            controller = ControllerDefinition(),
            resources = emptyList(),
            tasks = emptyList(),
            groups = emptyList(),
            options = emptyMap(),
            templates = emptyList(),
        )
        val LENIENT = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
