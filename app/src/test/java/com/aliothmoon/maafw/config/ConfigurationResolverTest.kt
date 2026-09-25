package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.TemplateTask
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationResolverTest {

    private val emptyJson = JsonObject(emptyMap())

    private fun task(
        name: String,
        resources: List<String> = emptyList(),
        optionNames: List<String> = emptyList(),
        groups: List<String> = emptyList(),
    ) = TaskDefinition(
        name = name,
        entry = "E_$name",
        label = name,
        description = null,
        groups = groups,
        optionNames = optionNames,
        pipelineOverride = emptyJson,
        controllers = emptyList(),
        resources = resources,
        defaultCheck = true,
    )

    private fun definition(
        tasks: List<TaskDefinition> = listOf(task("T1"), task("T2", resources = listOf("B服"))),
        options: Map<String, OptionDefinition> = emptyMap(),
        templates: List<ConfigurationTemplate> = emptyList(),
        resources: List<ResourceDefinition> = listOf(
            ResourceDefinition("官服", listOf("./base")),
            ResourceDefinition("B服", listOf("./bili")),
        ),
    ) = ProjectDefinition(
        name = "demo",
        version = "1",
        controller = ControllerDefinition(),
        resources = resources,
        tasks = tasks,
        groups = listOf(TaskGroupDefinition(name = "ungrouped", isUngrouped = true)),
        options = options,
        templates = templates,
    )

    @Test
    fun `initialize creates configuration from each preset`() {
        val def = definition(
            templates = listOf(
                ConfigurationTemplate(
                    name = "Daily",
                    label = "日常",
                    description = null,
                    tasks = listOf(TemplateTask("T1", enabled = true, optionValues = emptyMap())),
                ),
            ),
        )
        val result = ConfigurationResolver.initialize(def, UserConfiguration())
        assertTrue(result.initialized)
        assertEquals(1, result.configurations.size)
        assertEquals("日常", result.configurations.single().name)
        assertEquals("官服", result.activeResourceName)
        assertEquals(result.configurations.single().id, result.activeConfigurationId)
    }

    @Test
    fun `createFromTemplate filters tasks and uses custom name`() {
        val def = definition(
            templates = listOf(
                ConfigurationTemplate(
                    name = "P",
                    label = "模板",
                    description = null,
                    tasks = listOf(
                        TemplateTask("T1", true, emptyMap()),
                        TemplateTask("T2", false, emptyMap()),
                    ),
                ),
            ),
        )
        val created = ConfigurationResolver.createFromTemplate(
            definition = def,
            templateName = "P",
            configurationName = "自定义",
            taskNames = listOf("T1"),
        )
        assertNotNull(created)
        assertEquals("自定义", created!!.name)
        assertEquals(listOf("T1"), created.tasks.map { it.taskName })
    }

    @Test
    fun `reset task list clears values and restores unique preset checks`() {
        val def = definition(
            templates = listOf(
                ConfigurationTemplate(
                    name = "P",
                    label = "模板",
                    description = null,
                    tasks = listOf(
                        TemplateTask("T1", enabled = false, optionValues = emptyMap()),
                        TemplateTask("T2", enabled = true, optionValues = emptyMap()),
                    ),
                ),
            ),
        )
        val configuration = RunConfiguration(
            id = RunConfigurationId("c1"),
            name = "日常",
            tasks = listOf(
                ConfiguredTask(
                    taskName = "T1",
                    enabled = true,
                    optionValues = mapOf("option" to OptionValue.Inputs(mapOf("count" to "2"))),
                    customLabel = "别名",
                    instanceId = "i1",
                ),
                ConfiguredTask(
                    taskName = "T2",
                    enabled = false,
                    optionValues = mapOf("option" to OptionValue.SingleCase("a")),
                    instanceId = "i2",
                ),
            ),
        )

        val result = ConfigurationResolver.resetTaskList(def, configuration)

        assertEquals(listOf(false, true), result.tasks.map { it.enabled })
        assertTrue(result.tasks.all { it.optionValues.isEmpty() })
        assertEquals("别名", result.tasks[0].customLabel)
        assertEquals(listOf("i1", "i2"), result.tasks.map { it.instanceId })
    }

    @Test
    fun `reset task list keeps checks when task sequence differs from preset`() {
        val def = definition(
            templates = listOf(
                ConfigurationTemplate(
                    name = "P",
                    label = "模板",
                    description = null,
                    tasks = listOf(
                        TemplateTask("T1", enabled = false, optionValues = emptyMap()),
                        TemplateTask("T2", enabled = true, optionValues = emptyMap()),
                    ),
                ),
            ),
        )
        val configuration = RunConfiguration(
            id = RunConfigurationId("c1"),
            name = "日常",
            tasks = listOf(
                ConfiguredTask(
                    taskName = "T2",
                    enabled = true,
                    optionValues = mapOf("option" to OptionValue.SingleCase("a")),
                    instanceId = "i1",
                ),
                ConfiguredTask(
                    taskName = "T1",
                    enabled = true,
                    optionValues = mapOf("option" to OptionValue.SingleCase("b")),
                    instanceId = "i2",
                ),
            ),
        )

        val result = ConfigurationResolver.resetTaskList(def, configuration)

        assertEquals(listOf(true, true), result.tasks.map { it.enabled })
        assertTrue(result.tasks.all { it.optionValues.isEmpty() })
    }

    @Test
    fun `reset task list keeps checks when multiple presets match task sequence`() {
        val template = ConfigurationTemplate(
            name = "P",
            label = "模板",
            description = null,
            tasks = listOf(
                TemplateTask("T1", enabled = false, optionValues = emptyMap()),
                TemplateTask("T2", enabled = true, optionValues = emptyMap()),
            ),
        )
        val def = definition(templates = listOf(template, template.copy(name = "P2", label = "模板2")))
        val configuration = RunConfiguration(
            id = RunConfigurationId("c1"),
            name = "日常",
            tasks = listOf(
                ConfiguredTask("T1", enabled = true, mapOf("option" to OptionValue.SingleCase("a")), instanceId = "i1"),
                ConfiguredTask("T2", enabled = false, mapOf("option" to OptionValue.SingleCase("b")), instanceId = "i2"),
            ),
        )

        val result = ConfigurationResolver.resetTaskList(def, configuration)

        assertEquals(listOf(true, false), result.tasks.map { it.enabled })
        assertTrue(result.tasks.all { it.optionValues.isEmpty() })
    }

    @Test
    fun `resource selection falls back with warning when missing`() {
        val session = ConfigurationResolver.resolve(
            definition(),
            UserConfiguration(
                initialized = true,
                activeResourceName = "不存在",
                configurations = listOf(RunConfiguration(RunConfigurationId("c1"), "A")),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        assertEquals("官服", session.environment.resource?.name)
        assertTrue(
            session.diagnostics.any {
                it.message.isResource(R.string.diagnostic_resource_selection_missing)
            },
        )
    }

    @Test
    fun `task resource mismatch marks unavailable`() {
        val session = ConfigurationResolver.resolve(
            definition(),
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(
                    RunConfiguration(
                        id = RunConfigurationId("c1"),
                        name = "A",
                        tasks = listOf(ConfiguredTask("T2", instanceId = "i2")),
                    ),
                ),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        val task = session.activeConfiguration!!.tasks.single()
        assertFalse(task.applicable)
        assertTrue(task.unavailableReason.isResource(R.string.task_unavailable_resource))
    }

    @Test
    fun `select option without default falls back to first case`() {
        val option = OptionDefinition.Select(
            name = "mode",
            label = "模式",
            description = null,
            cases = listOf(
                OptionCaseDefinition("a", "A", null, emptyJson, emptyList()),
                OptionCaseDefinition("b", "B", null, emptyJson, emptyList()),
            ),
            defaultCase = null,
        )
        val def = definition(
            tasks = listOf(task("T1", optionNames = listOf("mode"))),
            options = mapOf("mode" to option),
        )
        val session = ConfigurationResolver.resolve(
            def,
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(
                    RunConfiguration(
                        id = RunConfigurationId("c1"),
                        name = "A",
                        tasks = listOf(ConfiguredTask("T1", instanceId = "i1")),
                    ),
                ),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        val editor = session.activeConfiguration!!.tasks.single().options.single()
        assertEquals(OptionKind.Select, editor.kind)
        // 用户没设值，value 仍是 null；选中态来自 effectiveDefaultCase 的回落
        assertNull(editor.value)
        assertEquals(listOf("a"), editor.cases.filter { it.active }.map { it.name })
    }

    @Test
    fun `switch default case activates branch`() {
        val option = OptionDefinition.Switch(
            name = "sw",
            label = "开关",
            description = null,
            cases = listOf(
                OptionCaseDefinition("Yes", "开", null, emptyJson, emptyList()),
                OptionCaseDefinition("No", "关", null, emptyJson, emptyList()),
            ),
            defaultCase = "Yes",
        )
        val def = definition(
            tasks = listOf(task("T1", optionNames = listOf("sw"))),
            options = mapOf("sw" to option),
        )
        val session = ConfigurationResolver.resolve(
            def,
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(
                    RunConfiguration(
                        id = RunConfigurationId("c1"),
                        name = "A",
                        tasks = listOf(ConfiguredTask("T1", instanceId = "i1")),
                    ),
                ),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        val cases = session.activeConfiguration!!.tasks.single().options.single().cases
        assertEquals(listOf(true, false), cases.map { it.active })
    }

    @Test
    fun `missing configured task is marked missingDefinition`() {
        val session = ConfigurationResolver.resolve(
            definition(),
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(
                    RunConfiguration(
                        id = RunConfigurationId("c1"),
                        name = "A",
                        tasks = listOf(ConfiguredTask("Nope", instanceId = "x")),
                    ),
                ),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )
        val task = session.activeConfiguration!!.tasks.single()
        assertTrue(task.missingDefinition)
        assertTrue(task.unavailableReason.isResource(R.string.task_unavailable_missing))
    }

    @Test
    fun `checkApplicability accepts empty resource filter`() {
        val def = definition()
        val t = def.task("T1")!!
        assertNull(ConfigurationResolver.checkApplicability(def, t, "官服"))
    }
}
