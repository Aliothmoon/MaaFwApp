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

    /** 进度行的任务名走 PI 本地化后的展示名，不露内部 name */
    @Test
    fun `progress uses the localized task label`() {
        val labeled = RunLogComposer().compose(
            RunnerEvent.Progress("VisitFriends", 1, 12),
            1,
            0,
            RunLogContext(currentTaskName = "🤝拜访好友"),
        )
        assertEquals(UiText.Verbatim("🤝拜访好友 1/12"), labeled?.text)
        // 拿不到展示名时退回内部名，宁可显示内部名也不显示空
        val bare = RunLogComposer().compose(RunnerEvent.Progress("VisitFriends", 1, 12), 1, 0, RunLogContext())
        assertEquals(UiText.Verbatim("VisitFriends 1/12"), bare?.text)
    }

    /** 停止标记是框架自己投的空任务，不能套上当前任务名多出一对「开始 / 完成」 */
    @Test
    fun `the framework stop mark task is not reported`() {
        assertEquals(RunLogKind.Success, callback("Tasker.Task.Succeeded", """{"entry":"AutoCollectSchedule"}""")?.kind)
        assertNull(callback("Tasker.Task.Starting", """{"entry":"MaaTaskerPostStop"}"""))
        assertNull(callback("Tasker.Task.Succeeded", """{"entry":"MaaTaskerPostStop"}"""))
        assertNull(callback("Tasker.Task.Failed", """{"entry":"MaaTaskerPostStop"}"""))
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
        assertNull(callback("Controller.Action.Succeeded", """{"action":"Screencap"}"""))
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

    /**
     * 实际是每个路径一对「开始 / 成功」交替着来，连续去重拦不住
     *
     * 知道本轮几个路径时只讲第一个的开始、最后一个的成功；失败照讲
     */
    @Test
    fun `interleaved per-path resource notifications report once for the whole load`() {
        val threePaths = context.copy(resourceBundleCount = 3)
        fun resource(message: String, path: String) =
            composer.compose(RunnerEvent.Callback(message, """{"path":"$path"}"""), ++nextId, clock, threePaths)

        val lines = listOf("/a", "/b", "/c").flatMap { path ->
            listOf(
                resource("Resource.Loading.Starting", path),
                resource("Resource.Loading.Succeeded", path),
            )
        }.filterNotNull()
        assertEquals(
            listOf(
                UiText.Resource(R.string.run_log_resource_loading, listOf("官服")),
                UiText.Resource(R.string.run_log_resource_loaded, listOf("官服")),
            ),
            lines.map { it.text },
        )

        composer.reset()
        assertEquals(RunLogKind.Info, resource("Resource.Loading.Starting", "/a")?.kind)
        assertEquals(RunLogKind.Error, resource("Resource.Loading.Failed", "/a")?.kind)
    }

    /** 与 MXU 一致：节点消息一秒几十条、只有事件名谁也看不懂，全份在 maa.log */
    @Test
    fun `node messages are dropped`() {
        assertNull(callback("Node.Recognition.Failed", """{"name":"NodeA"}"""))
    }

    @Test
    fun `unknown messages are dropped`() {
        assertNull(callback("Something.Brand.New"))
    }

    /** 丢掉的回调不参与去重：夹在两条相同合成行之间也不会让后一条露出来 */
    @Test
    fun `dropped callbacks do not break dedup`() {
        assertEquals(RunLogKind.Info, callback("Resource.Loading.Starting", """{"path":"/a"}""")?.kind)
        assertNull(callback("Node.Action.Starting", """{"name":"A"}"""))
        assertNull(callback("Resource.Loading.Starting", """{"path":"/b"}"""))
    }

    /**
     * 正文原样装进条目
     *
     * `$i18n` 查表、`{image}`、文件路径这几步有先后依赖、后两步还要 IO，
     * 都在调用方做完了（见 SessionViewModelTest）；合成器只管装
     */
    @Test
    fun `focus content is taken as-is`() {
        val entry = compose(RunnerEvent.Focus(FocusMessage(
                message = "Node.PipelineNode.Succeeded",
                content = "显影罐不足",
                channels = setOf(FocusChannel.Log),
                trace = false,
            )))
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

    /** go-service 拿不到 Context 时往 stdout 打 HTML 警告：按 focus 渲染、进关键档 */
    @Test
    fun `html an agent prints for the user is shown like a focus`() {
        val warning = """<span style="color: #ff0000;">🚨 警告：分辨率不符合要求！🚨</span> <br/>任务已强制停止"""
        val entry = compose(agentLine(warning))!!
        assertEquals(RunLogKind.Focus, entry.kind)
        assertEquals(UiText.Verbatim(warning), entry.text)
        assertEquals(true, entry.isEssential)
    }

    /** 它往往正是这一轮停下来的原因，刷屏期也不能被吞 */
    @Test
    fun `html for the user is not swallowed by an agent flood`() {
        repeat(AGENT_THRESHOLD) { index -> compose(agentLine("line $index"), 0) }
        assertNull(compose(agentLine("still flooding"), 0))
        assertEquals(RunLogKind.Focus, compose(agentLine("<b>stopped</b>"), 0)?.kind)
    }

    /** 终端日志与代码里的尖括号不算：带 ANSI 转义的、C++ 模板参数、traceback 的 `<module>` */
    @Test
    fun `angle brackets in logs are not mistaken for html`() {
        assertEquals(RunLogKind.Agent, compose(agentLine("\u001B[31m<span>colored log</span>\u001B[0m"))?.kind)
        assertEquals(RunLogKind.Agent, compose(agentLine("std::vector<int> size=3"))?.kind)
        assertEquals(
            RunLogKind.AgentError,
            compose(agentLine("""  File "agent/main.py", line 1, in <module>""", fromStderr = true))?.kind,
        )
    }

    /** 编排层的 connect 成功是关键档，跟设备连接同一档；child 自己的 stderr 仍不是 */
    @Test
    fun `agent connect is an essential success line using the exec basename`() {
        val entry = compose(
            RunnerEvent.AgentConnected(
                index = 1,
                total = 2,
                exec = "/data/app/~~x/lib/arm64/libcpp-algo.so",
            ),
        )
        assertEquals(RunLogKind.Success, entry?.kind)
        assertEquals(true, entry?.isEssential)
        assertEquals(
            UiText.Resource(R.string.run_log_agent_connected, listOf("libcpp-algo.so")),
            entry?.text,
        )
    }

    /** 配方写了 name 就显示它，空白视同没写 */
    @Test
    fun `agent connect prefers the profile name over the exec basename`() {
        val exec = "/data/app/~~x/lib/arm64/libcpp-algo.so"
        assertEquals(
            UiText.Resource(R.string.run_log_agent_connected, listOf("cpp-algo")),
            compose(RunnerEvent.AgentConnected(index = 1, total = 2, exec = exec, name = "cpp-algo"))?.text,
        )
        assertEquals(
            UiText.Resource(R.string.run_log_agent_connected, listOf("libcpp-algo.so")),
            compose(RunnerEvent.AgentConnected(index = 1, total = 2, exec = exec, name = " "))?.text,
        )
    }

    /** 被信号杀死的显示信号名，自己退出的显示退出码；都进关键档 */
    @Test
    fun `agent exit is an essential error line`() {
        val exec = "/data/app/~~x/lib/arm64/libcpp-algo.so"
        val signaled = compose(RunnerEvent.AgentExited(index = 1, exec = exec, exitCode = 139, name = "cpp-algo"))
        assertEquals(RunLogKind.Error, signaled?.kind)
        assertEquals(true, signaled?.isEssential)
        assertEquals(
            UiText.Resource(R.string.run_log_agent_exited_signal, listOf("cpp-algo", "SIGSEGV")),
            signaled?.text,
        )
        assertEquals(
            UiText.Resource(R.string.run_log_agent_exited_code, listOf("libcpp-algo.so", 1)),
            compose(RunnerEvent.AgentExited(index = 1, exec = exec, exitCode = 1))?.text,
        )
    }

    @Test
    fun `agent exit names the saved crash report`() {
        val entry = compose(
            RunnerEvent.AgentExited(
                index = 0,
                exec = "/x/libgo-service.so",
                exitCode = 134,
                crashReport = "agent_20261002_222045_24507.txt",
            ),
        )
        assertEquals(
            UiText.Resource(
                R.string.run_log_agent_crash_report,
                listOf(
                    UiText.Resource(R.string.run_log_agent_exited_signal, listOf("libgo-service.so", "SIGABRT")),
                    "agent_20261002_222045_24507.txt",
                ),
            ),
            entry?.text,
        )
    }

    /** traceback 一大段刚把滑窗打满，紧跟着的「已退出」不能被一起吞掉 */
    @Test
    fun `agent exit is shown even while agent output is flooding`() {
        repeat(AGENT_THRESHOLD) { compose(agentLine("trace $it", fromStderr = true), 0) }
        assertNull(compose(agentLine("more", fromStderr = true), 0))
        assertEquals(
            RunLogKind.Error,
            compose(RunnerEvent.AgentExited(index = 0, exec = "/x/libgo-service.so", exitCode = 134), 0)?.kind,
        )
    }

    /**
     * 特权进程攒批之后，洪泛滑窗必须按行计
     *
     * 按事件计的话一批 64 行只算 1，阈值永远踩不到，抑制器等于关掉了
     */
    @Test
    fun `a batched burst counts every line toward the flood window`() {
        val burst = (1..AGENT_THRESHOLD).joinToString("\n") { "line $it" }
        assertEquals(RunLogKind.Warning, compose(agentLine(burst), 0)?.kind)
    }

    /** 单行批的行数是 1，别把没有换行的那种算成 0 */
    @Test
    fun `a single line batch counts as one`() {
        assertEquals(1, RunnerEvent.AgentOutput("only", fromStderr = false).lineCount)
        assertEquals(3, RunnerEvent.AgentOutput("a\nb\nc", fromStderr = false).lineCount)
    }

    private companion object {
        const val AGENT_THRESHOLD = 15
        const val AGENT_WINDOW_MS = 2_000L
    }
}
