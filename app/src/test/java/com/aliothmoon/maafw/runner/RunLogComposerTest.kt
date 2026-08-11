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

    @Test
    fun `focus content resolves the pi translation table`() {
        val translated = RunLogComposer().compose(
            RunnerEvent.Focus(FocusMessage("\$tip_key", setOf(FocusChannel.Log))),
            1,
            0,
            RunLogContext(translations = mapOf("tip_key" to "显影罐不足")),
        )
        assertEquals(RunLogKind.Focus, translated?.kind)
        assertEquals(UiText.Verbatim("显影罐不足"), translated?.text)
    }

    /** 查无此键回落到键名本身，与加载期的 $i18n 处理一致 */
    @Test
    fun `unknown translation key falls back to the key`() {
        val entry = RunLogComposer().compose(
            RunnerEvent.Focus(FocusMessage("\$missing", setOf(FocusChannel.Log))),
            1,
            0,
            RunLogContext(),
        )
        assertEquals(UiText.Verbatim("missing"), entry?.text)
    }

    @Test
    fun `agent output floods are suppressed and then recover`() {
        repeat(AGENT_THRESHOLD - 1) { index ->
            assertEquals(RunLogKind.Agent, compose(RunnerEvent.AgentOutput("line $index"), 0)?.kind)
        }
        // 触顶这一条换成告警，不静悄悄地少显示
        assertEquals(RunLogKind.Warning, compose(RunnerEvent.AgentOutput("flood"), 0)?.kind)
        assertNull(compose(RunnerEvent.AgentOutput("still flooding"), 0))

        // 滑窗走空后恢复，并且明说恢复了
        val afterWindow = AGENT_WINDOW_MS + 1
        assertEquals(RunLogKind.Warning, compose(RunnerEvent.AgentOutput("back"), afterWindow)?.kind)
        assertEquals(RunLogKind.Agent, compose(RunnerEvent.AgentOutput("normal"), afterWindow)?.kind)
    }

    private companion object {
        const val AGENT_THRESHOLD = 15
        const val AGENT_WINDOW_MS = 2_000L
    }
}
