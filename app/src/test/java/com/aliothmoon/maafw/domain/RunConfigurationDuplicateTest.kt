package com.aliothmoon.maafw.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RunConfigurationDuplicateTest {
    @Test
    fun duplicate_preservesTaskSettingsWithIndependentIds() {
        val original = RunConfiguration(
            id = RunConfigurationId("original"),
            name = "Daily",
            tasks = listOf(
                ConfiguredTask(
                    taskName = "VisitFriends",
                    enabled = false,
                    optionValues = mapOf("mode" to OptionValue.SingleCase("fast")),
                    instanceId = "original-task",
                ),
            ),
        )

        val duplicated = original.duplicate(
            id = RunConfigurationId("duplicated"),
            name = "Daily copy",
        )

        assertEquals(RunConfigurationId("duplicated"), duplicated.id)
        assertEquals("Daily copy", duplicated.name)
        assertEquals(
            original.tasks.map { it.copy(instanceId = "ignored") },
            duplicated.tasks.map { it.copy(instanceId = "ignored") },
        )
        assertNotEquals(original.tasks.single().instanceId, duplicated.tasks.single().instanceId)
    }
}
