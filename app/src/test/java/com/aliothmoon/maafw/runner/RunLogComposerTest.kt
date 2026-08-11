package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 合成规则对齐桌面端 MXU 的 `useMaaCallbackLogger` */
class RunLogComposerTest {

    private val composer = RunLogComposer()
    private val context = RunLogContext(currentTaskName = "启动应用", resourceLabel = "官服")

    private var nextId = 0L
    private var clock = 0L

    private fun compose(event: RunnerEvent, atMillis: Long = clock): RunLogEntry? =
        composer.compose(event, ++nextId, atMillis, context)

    private fun agentLine(line: String, fromStderr: Boolean = false) =
        RunnerEvent.AgentOutput(line, fromStderr)

    private fun callback(message: String, details: String = "{}") =
        compose(RunnerEvent.Callback(message, details))

    @Test
    fun `task messages become sentences with the running task name`() {
        assertEquals(
            RunLogEntry(1, 0, RunLogKind.Info, UiText.Resource(R.string.run_log_task_starting, listOf("启动应用"))),
            callback("Tasker.Task.Starting", """{"entry":"Start"}"""),
        )
        assertEquals(RunLogKind.Success, callback("Tasker.Task.Succeeded")?.kind)
        assertEquals(RunLogKind.Error, callback("Tasker.Task.Failed")?.kind)
    }

    /** 拿不到当前任务名就退回 PI 的 entry，宁可显示内部名也不显示空 */
    @Test
    fun `task name falls back to the pipeline entry`() {
        val entry = RunLogComposer().compose(
            RunnerEvent.Callback("Tasker.Task.Starting", """{"entry":"Start"}"""),
            1,
            0,
            RunLogContext(),
        )
        assertEquals(UiText.Resource(R.string.run_log_task_starting, listOf("Start")), entry?.text)
    }

    @Test
    fun `only the connect action is spelled out`() {
        assertEquals(
            RunLogKind.Info,
            callback("Controller.Action.Starting", """{"action":"Connect"}""")?.kind,
        )
        assertEquals(
            RunLogKind.Success,
            callback("Controller.Action.Succeeded", """{"action":"Connect"}""")?.kind,
        )
        // 截图每帧都来，讲出来就是刷屏
        assertEquals(
            RunLogKind.Verbose,
            callback("Controller.Action.Succeeded", """{"action":"Screencap"}""")?.kind,
        )
    }

    /** 资源多路径逐条发同样的通知，合成后连着重复只留第一条 */
    @Test
    fun `repeated resource notifications collapse into one`() {
        assertEquals(RunLogKind.Info, callback("Resource.Loading.Starting", """{"path":"/a"}""")?.kind)
        assertNull(callback("Resource.Loading.Starting", """{"path":"/b"}"""))
        assertNull(callback("Resource.Loading.Starting", """{"path":"/c"}"""))
        assertEquals(RunLogKind.Success, callback("Resource.Loading.Succeeded", """{"path":"/a"}""")?.kind)
        assertNull(callback("Resource.Loading.Succeeded", """{"path":"/b"}"""))
    }

    /** MXU 把节点消息直接丢掉；这里降级留着，「全部」档可见 */
    @Test
    fun `node messages stay raw and keep their details`() {
        val entry = callback("Node.Recognition.Failed", """{"name":"NodeA"}""")
        assertEquals(RunLogKind.Verbose, entry?.kind)
        assertEquals(UiText.Verbatim("Node.Recognition.Failed"), entry?.text)
        assertEquals("""{"name":"NodeA"}""", entry?.detail)
    }

    @Test
    fun `unknown messages are kept raw rather than dropped`() {
        assertEquals(RunLogKind.Verbose, callback("Something.Brand.New")?.kind)
    }

    /**
     * 正文原样装进条目
     *
     * `$i18n` 查表、`{image}`、文件路径这几步有先后依赖、后两步还要 IO，
     * 都在调用方做完了（见 SessionViewModelTest）；合成器只管装
     */
    @Test
    fun `focus content is taken as-is`() {
        val entry = compose(RunnerEvent.Focus(FocusMessage("显影罐不足", setOf(FocusChannel.Log))))
        assertEquals(RunLogKind.Focus, entry?.kind)
        assertEquals(UiText.Verbatim("显影罐不足"), entry?.text)
    }

    @Test
    fun `agent output floods are suppressed and then recover`() {
        repeat(AGENT_THRESHOLD - 1) { index ->
            assertEquals(RunLogKind.Agent, compose(agentLine("line $index"), 0)?.kind)
        }
        // 触顶这一条换成告警，不静悄悄地少显示
        assertEquals(RunLogKind.Warning, compose(agentLine("flood"), 0)?.kind)
        assertNull(compose(agentLine("still flooding"), 0))

        // 滑窗走空后恢复，并且明说恢复了
        val afterWindow = AGENT_WINDOW_MS + 1
        assertEquals(RunLogKind.Warning, compose(agentLine("back"), afterWindow)?.kind)
        assertEquals(RunLogKind.Agent, compose(agentLine("normal"), afterWindow)?.kind)
    }

    /** stderr 上的话多半不是 agent 自己说的：链接器警告就走这条 */
    @Test
    fun `stderr lines are told apart from what the agent prints`() {
        assertEquals(RunLogKind.Agent, compose(agentLine("reco hit"))?.kind)
        assertEquals(
            RunLogKind.AgentError,
            compose(agentLine("WARNING: linker: unused DT entry", fromStderr = true))?.kind,
        )
    }

    /** 洪泛滑窗按两条流合起来算：刷屏就是刷屏，不分从哪条管道出来 */
    @Test
    fun `the flood window counts both streams together`() {
        repeat(AGENT_THRESHOLD - 1) { index ->
            compose(agentLine("out $index", fromStderr = index % 2 == 0), 0)
        }
        assertEquals(RunLogKind.Warning, compose(agentLine("flood"), 0)?.kind)
    }

    /** agent 的两条流都不进「关键」档——它和原始转储同级 */
    @Test
    fun `agent output is not essential`() {
        assertEquals(false, compose(agentLine("out"))!!.isEssential)
        assertEquals(false, compose(agentLine("err", fromStderr = true))!!.isEssential)
    }

    private companion object {
        const val AGENT_THRESHOLD = 15
        const val AGENT_WINDOW_MS = 2_000L
    }
}
