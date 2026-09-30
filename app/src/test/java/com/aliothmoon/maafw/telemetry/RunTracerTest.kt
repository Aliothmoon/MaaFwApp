package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.maa.MaaMsg
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusMessage
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RuntimeTask
import io.mockk.every
import io.mockk.mockk
import io.sentry.ISpan
import io.sentry.SpanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记下对一条 Span 的全部写入；子 Span 同样是它 */
private class SpanRecord(val op: String, val description: String?) {
    val data = mutableMapOf<String, Any?>()
    val tags = mutableMapOf<String, String>()
    val children = mutableListOf<SpanRecord>()
    var status: SpanStatus? = null

    val span: ISpan = mockk<ISpan>(relaxed = true).also { span ->
        every { span.setData(any(), any()) } answers { data[firstArg()] = secondArg() }
        every { span.setTag(any(), any()) } answers { tags[firstArg()] = secondArg() }
        every { span.startChild(any<String>(), any<String>()) } answers {
            SpanRecord(firstArg(), secondArg()).also(children::add).span
        }
        every { span.finish(any<SpanStatus>()) } answers { status = firstArg() }
    }
}

class RunTracerTest {

    private val transactions = mutableListOf<SpanRecord>()
    private var now = 1_000L
    private val tracer = RunTracer(
        startTransaction = { name, op -> SpanRecord(op, name).also(transactions::add).span },
        clock = { now },
    )

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1.0.0",
        controller = ControllerDefinition(name = "Android", type = "ADB"),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = listOf(
            RuntimeTask("启动游戏", "Start", emptyList(), telemetryOptions = mapOf("server" to "cn")),
            RuntimeTask("领取奖励", "Reward", emptyList()),
        ),
    )

    init {
        tracer.begin("e1", plan)
    }

    private fun emit(event: RunnerEvent, executionId: String = "e1") = tracer.onEvent(executionId, event)

    private fun finish(result: ExecutionResult, executionId: String = "e1") =
        emit(RunnerEvent.ExecutionFinished(result), executionId)

    private fun callback(message: String, details: String) = emit(RunnerEvent.Callback(message, details))

    private val transaction get() = transactions.single()

    @Test
    fun `一轮对应一条事务，每个任务一条子 Span`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 2))
        callback(MaaMsg.TASKER_TASK_STARTING, """{"task_id":7,"entry":"Start"}""")
        emit(RunnerEvent.TaskFinished(0, success = true))
        emit(RunnerEvent.Progress("领取奖励", 1, 2))
        emit(RunnerEvent.TaskFinished(1, success = false))
        finish(ExecutionResult.CompletedWithFailures(emptyList()))

        assertEquals(RunTracer.RUN_OP, transaction.op)
        assertEquals(RunTracer.RUN_NAME, transaction.description)
        assertEquals(2, transaction.data["task_count"])
        assertEquals("启动游戏,领取奖励", transaction.data["tasks"])
        assertEquals("ADB", transaction.tags["controller.type"])
        assertEquals(SpanStatus.INTERNAL_ERROR, transaction.status)
        assertEquals("failure", transaction.data["result"])

        val (first, second) = transaction.children
        assertEquals(RunTracer.TASK_OP, first.op)
        assertEquals("启动游戏", first.description)
        assertEquals(7L, first.data["task_id"])
        assertEquals("cn", first.data["option.server"])
        assertEquals(SpanStatus.OK, first.status)
        assertEquals(SpanStatus.INTERNAL_ERROR, second.status)
    }

    /** 取消时还在跑的任务没有终局，按取消收 */
    @Test
    fun `取消时进行中的任务记为取消`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 2))
        finish(ExecutionResult.Cancelled(emptyList()))

        assertEquals(SpanStatus.CANCELLED, transaction.children.single().status)
        assertEquals(SpanStatus.CANCELLED, transaction.status)
        assertEquals("cancelled", transaction.data["result"])
    }

    /** 计划没登记（遥测中途打开）也照样出事务，只是缺任务清单与选项 */
    @Test
    fun `没有计划时照样记事务`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1), executionId = "e2")
        emit(RunnerEvent.TaskFinished(0, success = true), executionId = "e2")
        finish(ExecutionResult.Completed(emptyList()), executionId = "e2")

        assertNull(transaction.data["task_count"])
        assertEquals(SpanStatus.OK, transaction.children.single().status)
        assertEquals(SpanStatus.OK, transaction.status)
    }

    /** 没有 focus 模板的失败节点按协议默认上报，识别阶段失败、带步骤耗时 */
    @Test
    fun `失败节点默认上报`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1))
        callback(MaaMsg.NODE_PIPELINE_NODE_STARTING, """{"task_id":7,"node_id":3,"name":"FindStart"}""")
        now += 250
        callback(MaaMsg.NODE_PIPELINE_NODE_FAILED, """{"task_id":7,"node_id":3,"name":"FindStart"}""")
        callback(MaaMsg.NODE_PIPELINE_NODE_SUCCEEDED, """{"task_id":7,"node_id":4,"name":"Other"}""")

        val node = transaction.children.single().children.single()
        assertEquals(RunTracer.NODE_OP, node.op)
        assertEquals("FindStart", node.description)
        assertEquals("recognition", node.data["stage"])
        assertEquals(250L, node.data["duration_ms"])
        assertEquals("启动游戏", node.data["task"])
        assertEquals(SpanStatus.INTERNAL_ERROR, node.status)
    }

    @Test
    fun `命中后动作失败用命中的节点名`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1))
        callback(
            MaaMsg.NODE_PIPELINE_NODE_FAILED,
            """{"task_id":7,"node_id":3,"name":"Search","node_details":{"name":"ClickStart"}}""",
        )

        val node = transaction.children.single().children.single()
        assertEquals("ClickStart", node.description)
        assertEquals("Search", node.data["search_node"])
        assertEquals("action", node.data["stage"])
    }

    /** focus 里显式写了 trace 的以它为准：开了的成功节点也报，关了的失败节点不报 */
    @Test
    fun `focus 的 trace 覆盖默认值`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1))
        emit(focus(MaaMsg.NODE_ACTION_SUCCEEDED, trace = true, details = """{"task_id":7,"node_id":3,"name":"Collect"}"""))
        emit(focus(MaaMsg.NODE_PIPELINE_NODE_FAILED, trace = false, details = """{"task_id":7,"node_id":4,"name":"Quiet"}"""))

        val node = transaction.children.single().children.single()
        assertEquals("Collect", node.description)
        assertEquals(SpanStatus.OK, node.status)
        assertNull(node.data["stage"])
    }

    @Test
    fun `别的轮次的事件不落到当前事务上`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1))
        emit(RunnerEvent.TaskFinished(0, success = false), executionId = "stale")
        finish(ExecutionResult.Completed(emptyList()), executionId = "stale")

        assertNull(transaction.status)
        assertNull(transaction.children.single().status)
    }

    /** 上一轮的终局丢了，新一轮开跑时按取消收掉，不挂到新事务上 */
    @Test
    fun `上一轮没收尾时新一轮先按取消结掉`() {
        emit(RunnerEvent.Progress("启动游戏", 0, 1))
        emit(RunnerEvent.Progress("启动游戏", 0, 1), executionId = "e2")

        assertEquals(2, transactions.size)
        assertEquals(SpanStatus.CANCELLED, transactions[0].status)
        assertTrue(transactions[1].children.single().status == null)
    }

    private fun focus(message: String, trace: Boolean, details: String) = RunnerEvent.Focus(
        FocusMessage(message = message, content = "", channels = setOf(FocusChannel.Log), trace = trace),
        details,
    )
}
