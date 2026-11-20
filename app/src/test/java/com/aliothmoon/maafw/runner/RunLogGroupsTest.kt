package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切法对齐 MaaMeow 的 `groupLogs`（841b6081），换成本项目的任务起止标记 */
class RunLogGroupsTest {

    private data class L(
        val text: String,
        override val kind: RunLogKind = RunLogKind.Info,
        override val task: RunLogTaskMark? = null,
        override val atMillis: Long = 0,
    ) : RunLogLine

    private fun start(name: String, at: Long = 0) = L("start $name", task = RunLogTaskMark(RunLogTaskEdge.Start, name), atMillis = at)

    private fun done(name: String, at: Long = 0) =
        L("done $name", RunLogKind.Success, RunLogTaskMark(RunLogTaskEdge.Succeeded, name), at)

    private fun failed(name: String, at: Long = 0) =
        L("failed $name", RunLogKind.Error, RunLogTaskMark(RunLogTaskEdge.Failed, name), at)

    private fun List<RunLogGroup<L>>.texts() = map { group -> group.lines.map { it.text } }

    @Test
    fun `lines split by task start and end`() {
        val groups = groupRunLog(
            listOf(
                L("connecting"),
                L("resource loaded", RunLogKind.Success),
                start("A", at = 1_000),
                L("a1"),
                L("agent says hi", RunLogKind.Agent),
                L("fps low", RunLogKind.Warning),
                done("A", at = 66_000),
                L("after A"),
                start("B", at = 70_000),
                failed("B", at = 75_000),
            ),
        )

        assertEquals(listOf(null, "A", "B"), groups.map { it.title })
        assertEquals(
            listOf(
                listOf("connecting", "resource loaded"),
                // 成功的结束行不进组，用时已在表头；结束后补的行仍归这个任务
                listOf("a1", "agent says hi", "fps low", "after A"),
                // 失败的结束行留在组里，原因与 details 要看得见
                listOf("failed B"),
            ),
            groups.texts(),
        )
        val a = groups[1]
        assertEquals(1, a.warnings)
        assertEquals(65_000L, a.elapsedMillis)
        assertEquals(RunLogTaskStatus.Succeeded, a.status(running = true, isLast = false))
        assertEquals(RunLogTaskStatus.Failed, groups[2].status(running = true, isLast = true))
        assertEquals(5_000L, groups[2].elapsedMillis)
    }

    @Test
    fun `an unfinished group is running only while the round runs and only when last`() {
        val groups = groupRunLog(listOf(start("A"), L("a1"), start("B"), L("b1")))

        assertNull(groups[1].end)
        assertNull(groups[1].elapsedMillis)
        assertEquals(RunLogTaskStatus.Running, groups[1].status(running = true, isLast = true))
        assertEquals(RunLogTaskStatus.Interrupted, groups[1].status(running = false, isLast = true))
        // 串行投递，后一个开始了前一个还没结束行，只能是断掉的
        assertEquals(RunLogTaskStatus.Interrupted, groups[0].status(running = true, isLast = false))
    }

    /** 500 条滑窗挤掉了起点行：窗口里第一段没有标题，冒出来的结束行当普通行留着 */
    @Test
    fun `a group whose start slid out of the window becomes untitled`() {
        val groups = groupRunLog(listOf(L("a7"), L("a8"), done("A"), start("B"), L("b1")))

        assertEquals(listOf(null, "B"), groups.map { it.title })
        assertNull(groups[0].start)
        assertNull(groups[0].end)
        assertEquals(listOf("a7", "a8", "done A"), groups[0].lines.map { it.text })
        assertEquals(listOf("b1"), groups[1].lines.map { it.text })
    }

    @Test
    fun `a second end line in the same group is kept as a plain line`() {
        val group = groupRunLog(listOf(start("A"), done("A"), failed("A"))).single()

        assertEquals(RunLogTaskStatus.Succeeded, group.status(running = false, isLast = true))
        assertEquals(listOf("failed A"), group.lines.map { it.text })
    }

    @Test
    fun `a task without any lines is a header-only group`() {
        val group = groupRunLog(listOf(start("A"), done("A"))).single()

        assertEquals("A", group.title)
        assertTrue(group.lines.isEmpty())
    }

    @Test
    fun `finished groups collapse by default except the last and the failed`() {
        val groups = groupRunLog(listOf(start("A"), done("A"), start("B"), failed("B"), start("C"), L("c1")))

        assertFalse(groups[0].expandedByDefault(running = true, isLast = false))
        assertTrue(groups[1].expandedByDefault(running = true, isLast = false))
        assertTrue(groups[2].expandedByDefault(running = true, isLast = true))
        // 收尾了也一样：最后一组还是展开着
        assertTrue(groups[2].expandedByDefault(running = false, isLast = true))
    }

    /** 旧版本写的会话文件没有任务标记：整份落进一个无标题组，等于原来的平铺 */
    @Test
    fun `session lines without marks degrade to a single flat group`() {
        val lines = listOf(
            RunSessionRecord.Line(1, RunLogKind.Info, "任务开始: 清体力"),
            RunSessionRecord.Line(2, RunLogKind.Warning, "帧率低"),
            RunSessionRecord.Line(3, RunLogKind.Success, "任务完成: 清体力"),
        )

        val group = groupRunLog(lines).single()
        assertNull(group.title)
        assertEquals(lines, group.lines)
        assertEquals(1, group.warnings)
    }

    @Test
    fun `an empty log has no groups`() {
        assertTrue(groupRunLog(emptyList<L>()).isEmpty())
    }
}
