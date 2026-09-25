package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 补完顺序的回归闸门
 *
 * 顺序按协议 3.3「Client 处理流程」：查表 → 读外部正文 → **最后**替换占位符。
 * 之前反了（解析时就替换），译文与文件正文里的 `{name}` 永远轮不到替换
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusDispatcherTest {

    private val definition = ProjectDefinition(
        name = "demo",
        version = "1",
        controller = ControllerDefinition(),
        resources = listOf(ResourceDefinition("base", listOf("./base"))),
        tasks = emptyList(),
        groups = listOf(TaskGroupDefinition(name = "ungrouped", isUngrouped = true)),
        options = emptyMap(),
        templates = emptyList(),
        translations = mapOf(
            "tip.plain" to "显影罐不足",
            "tip.with_placeholder" to "{name} 没跑成",
            "tip.file" to "./docs/tip.md",
        ),
    )

    private fun TestScope.dispatcherWith(
        runner: RecordingEventRunnerPort,
        resolver: FocusContentResolver = PassthroughFocusContentResolver,
    ) = FocusDispatcher(
        projectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
        resolver = resolver,
        runnerPort = runner,
        scope = backgroundScope,
    )


    /**
     * 没引 Turbine，就地收一份补完结果；每条用例只发一条事件
     *
     * 跑在 UnconfinedTestDispatcher 上：两处 collect 都要在 emit 之前真正订阅上，
     * SharedFlow 的 replay 是 0，晚一步这条就丢了
     */
    private fun TestScope.completed(
        runner: RecordingEventRunnerPort,
        dispatcher: FocusDispatcher,
        event: RunnerEvent.Focus,
    ): FocusMessage {
        val received = mutableListOf<FocusMessage>()
        backgroundScope.launch { dispatcher.resolved.collect { received += it } }
        advanceUntilIdle()
        runner.emit(event)
        advanceUntilIdle()
        return received.single()
    }

    private fun focus(content: String, placeholders: Map<String, String> = emptyMap()) =
        RunnerEvent.Focus(
            FocusMessage(
                message = "Node.PipelineNode.Succeeded",
                content = content,
                channels = setOf(FocusChannel.Log),
                trace = false,
                placeholders = placeholders,
            ),
        )

    @Test
    fun `plain text passes through untouched`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val result = completed(runner, dispatcherWith(runner), focus("显影罐不足"))
        assertEquals("显影罐不足", result.content)
    }

    @Test
    fun `translation key is resolved`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val result = completed(runner, dispatcherWith(runner), focus("\$tip.plain"))
        assertEquals("显影罐不足", result.content)
    }

    /** 查无此键回落键名本身，与加载期物化 label/description 的处理一致 */
    @Test
    fun `unknown translation key falls back to the key`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val result = completed(runner, dispatcherWith(runner), focus("\$tip.missing"))
        assertEquals("tip.missing", result.content)
    }

    /** 这条是 #5 的回归闸门：译文里的占位符必须还能替换 */
    @Test
    fun `placeholders inside a translated body are still substituted`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val event = focus("\$tip.with_placeholder", mapOf("name" to "NodeA"))
        assertEquals("NodeA 没跑成", completed(runner, dispatcherWith(runner), event).content)
    }

    /** 同上，换成读进来的文件正文 */
    @Test
    fun `placeholders inside a file body are still substituted`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val resolver = RecordingFocusContentResolver(fileBody = "{name} 的现场")
        val event = focus("./docs/tip.md", mapOf("name" to "NodeA"))
        assertEquals("NodeA 的现场", completed(runner, dispatcherWith(runner, resolver), event).content)
    }

    /** 查表结果本身就是个文件路径——所以查表必须早于读文件 */
    @Test
    fun `a translation resolving to a file path is then loaded`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val resolver = RecordingFocusContentResolver(fileBody = "文件正文")
        val result = completed(runner, dispatcherWith(runner, resolver), focus("\$tip.file"))
        assertEquals("文件正文", result.content)
        assertEquals(listOf("./docs/tip.md"), resolver.requests)
    }

    @Test
    fun `image placeholder goes through the resolver`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val resolver = RecordingFocusContentResolver(imageUri = "file:///tmp/shot.png")
        val result = completed(runner, dispatcherWith(runner, resolver), focus("![]({image})"))
        assertEquals("![](file:///tmp/shot.png)", result.content)
    }

    /** 直接文本不该白跑一趟 IO */
    @Test
    fun `plain text never reaches the resolver`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val resolver = RecordingFocusContentResolver()
        completed(runner, dispatcherWith(runner, resolver), focus("显影罐不足"))
        assertEquals(emptyList<String>(), resolver.requests)
    }

    /** 渠道原样带过去：补完不该动它 */
    @Test
    fun `channels survive completion`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val channels = setOf(FocusChannel.Toast, FocusChannel.Notification)
        val event = RunnerEvent.Focus(
            FocusMessage(
                message = "Node.PipelineNode.Succeeded",
                content = "x",
                channels = channels,
                trace = false,
            ),
        )
        assertEquals(channels, completed(runner, dispatcherWith(runner), event).channels)
    }

    /** 补完带 IO 时，日志那条流里的 focus 仍排在它之后的原始事件与终局 marker 前面 */
    @Test
    fun `recording keeps completed focus in order with the raw events`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingEventRunnerPort()
        val slowResolver = object : FocusContentResolver {
            override suspend fun resolve(content: String): String {
                delay(1_000)
                return "读到的正文"
            }
        }
        val dispatcher = dispatcherWith(runner, slowResolver)
        val recorded = mutableListOf<RunnerEventEnvelope>()
        backgroundScope.launch { dispatcher.recording.collect { recorded += it } }
        advanceUntilIdle()

        runner.emit(focus("./docs/tip.md"), "e1", "启动")
        runner.emit(RunnerEvent.Log("之后的一行"), "e1", "启动")
        runner.emit(RunnerEvent.ExecutionFinished, "e1")
        // 补完跑在 backgroundScope 上，advanceUntilIdle 不推它的虚拟时间
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()

        assertEquals(
            listOf("读到的正文", "之后的一行", "ExecutionFinished"),
            recorded.map {
                when (val event = it.event) {
                    is RunnerEvent.Focus -> event.focus.content
                    is RunnerEvent.Log -> event.message
                    else -> event.toString()
                }
            },
        )
        assertEquals(listOf("e1", "e1", "e1"), recorded.map { it.executionId })
        assertEquals("启动", recorded.first().taskLabel)
    }
}
