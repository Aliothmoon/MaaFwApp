package com.aliothmoon.maafw.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredTaskDuplicateRenameTest {

    private val source = ConfiguredTask(
        taskName = "VisitFriends",
        enabled = false,
        optionValues = mapOf("mode" to OptionValue.SingleCase("fast")),
        instanceId = "t1",
    )

    private fun config(task: ConfiguredTask) = RunConfiguration(
        id = RunConfigurationId("c"),
        name = "C",
        tasks = listOf(task),
    )

    @Test
    fun duplicateTask_insertsCopyAfterSourceWithNewIdAndSameSettings() {
        val result = config(source).duplicateTask("t1", "VisitFriends (Copy)")

        // 源不变、副本插在源后
        assertEquals(2, result.tasks.size)
        assertEquals("t1", result.tasks[0].instanceId)
        assertEquals(source, result.tasks[0])
        val copy = result.tasks[1]
        assertNotEquals("t1", copy.instanceId)
        // 继承 taskName/启用/选项，customLabel 用传入值
        assertEquals("VisitFriends", copy.taskName)
        assertEquals(false, copy.enabled)
        assertEquals(source.optionValues, copy.optionValues)
        assertEquals("VisitFriends (Copy)", copy.customLabel)
    }

    @Test
    fun duplicateTask_unknownInstanceIsNoOp() {
        val original = config(source)
        assertEquals(original, original.duplicateTask("nope", "X (Copy)"))
    }

    @Test
    fun renameTask_setsCustomLabelAndKeepsTaskName() {
        val task = config(source).renameTask("t1", "My Visit").tasks.single()
        assertEquals("My Visit", task.customLabel)
        // 规范名不动（pipeline 引用不受影响）
        assertEquals("VisitFriends", task.taskName)
    }

    @Test
    fun renameTask_blankClearsCustomLabel() {
        val named = source.copy(customLabel = "Old")
        val task = config(named).renameTask("t1", "   ").tasks.single()
        assertNull(task.customLabel)
    }

    @Test
    fun renameTask_trimsWhitespace() {
        val task = config(source).renameTask("t1", "  trimmed  ").tasks.single()
        assertEquals("trimmed", task.customLabel)
    }

    @Test
    fun uniqueCopyLabel_stripsExistingCopySuffixSoNoDoubleSuffix() {
        val suffix = " (Copy)"
        assertEquals("A (Copy)", uniqueCopyLabel("A", suffix, emptyList()))
        // 复制副本不叠成「(Copy) (Copy)」
        assertEquals("A (Copy)", uniqueCopyLabel("A (Copy)", suffix, emptyList()))
        assertEquals("A (Copy)", uniqueCopyLabel("A (Copy) 2", suffix, emptyList()))
    }

    @Test
    fun uniqueCopyLabel_appendsNumberOnCollision() {
        val suffix = " (Copy)"
        assertEquals("A (Copy) 2", uniqueCopyLabel("A", suffix, listOf("A (Copy)")))
        assertEquals(
            "A (Copy) 3",
            uniqueCopyLabel("A", suffix, listOf("A (Copy)", "A (Copy) 2")),
        )
    }
}
