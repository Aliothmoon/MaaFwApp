package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.AgentDefinition
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.DirectoryProjectSource
import com.aliothmoon.maafw.project.ProjectLoadResult
import com.aliothmoon.maafw.project.ProjectLoader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class RunPlanBuilderTest {

    companion object {
        private lateinit var definition: ProjectDefinition

        @JvmStatic
        @BeforeClass
        fun loadProject() {
            // 夹具固定在 test/fixtures，不随打包资源更换而变；locale 显式固定，不依赖运行机默认语言
            val result = ProjectLoader(
                DirectoryProjectSource(File("src/test/fixtures/PI/M9A")),
                localeProvider = { "zh-CN" },
            ).load()
            definition = (result as ProjectLoadResult.Ready).definition
        }
    }

    private fun configWith(vararg tasks: ConfiguredTask): UserConfiguration {
        val id = RunConfigurationId("test")
        return UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(RunConfiguration(id, "测试", tasks.toList())),
            activeConfigurationId = id,
        )
    }

    @Test
    fun `无活动配置映射为 NoExecutableTasks`() {
        val result = RunPlanBuilder.build(definition, UserConfiguration(initialized = true))
        assertTrue(result is RunPlanResult.NoExecutableTasks)
    }

    @Test
    fun `夹具 PI 的 agent 声明原样冻结进 RunPlan`() {
        val result = RunPlanBuilder.build(definition, configWith(ConfiguredTask("启动游戏")))
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        val agent = (result as RunPlanResult.Success).plan.agents.single()
        // child_exec 与 child_args 都原样透传，Android 上不解释也不改写
        assertEquals("uv", agent.childExec)
        assertEquals(listOf("run", "python", "agent/bootstrap.py"), agent.childArgs)
    }

    @Test
    fun `多个 agent 按声明顺序进 RunPlan`() {
        // 顺序即与运行时描述 runtimes[] 的配对依据，不能重排
        val withAgents = definition.copy(
            agents = listOf(
                AgentDefinition("go-service", emptyList()),
                AgentDefinition("cpp-algo", listOf("--fast")),
            ),
        )
        val result = RunPlanBuilder.build(withAgents, configWith(ConfiguredTask("启动游戏")))
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        val plan = (result as RunPlanResult.Success).plan
        assertEquals(listOf("go-service", "cpp-algo"), plan.agents.map { it.childExec })
        assertEquals(listOf("--fast"), plan.agents.last().childArgs)
    }

    @Test
    fun `无 agent 的 PI 编出空 agent 列表`() {
        val result = RunPlanBuilder.build(
            definition.copy(agents = emptyList()),
            configWith(ConfiguredTask("启动游戏")),
        )
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        assertTrue((result as RunPlanResult.Success).plan.agents.isEmpty())
    }

    @Test
    fun `全部禁用映射为 NoExecutableTasks`() {
        val result = RunPlanBuilder.build(
            definition,
            configWith(ConfiguredTask("启动游戏", enabled = false)),
        )
        assertTrue(result is RunPlanResult.NoExecutableTasks)
    }

    @Test
    fun `switch 活动分支的 input placeholder 替换进 pipeline override`() {
        val result = RunPlanBuilder.build(definition, configWith(customStageTask()))
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        val plan = (result as RunPlanResult.Success).plan
        assertEquals(1, plan.tasks.size)

        // 嵌入文本 placeholder 保持字符串："{章节号}-{关卡号}" -> "3-9"
        val stagePatch = plan.tasks[0].pipelineOverrides.firstOrNull { patch ->
            patch.stageValue() == "3-9"
        }
        assertTrue("应包含替换后的 stage patch", stagePatch != null)
    }

    @Test
    fun `未命中 input 的 placeholder 原样透传且不阻断编译`() {
        // M9A 在 override 里写 "{节点名}<{输入名}" 运行期表达式，节点引用不是 input
        val result = RunPlanBuilder.build(definition, configWith(customStageTask()))
        assertTrue("表达式 placeholder 不应产生 Invalid: $result", result is RunPlanResult.Success)
        val plan = (result as RunPlanResult.Success).plan
        val expression = plan.tasks[0].pipelineOverrides.firstNotNullOfOrNull { patch ->
            patch.expressionValue()
        }
        assertEquals("{CombatStageOCRInternal}<3", expression)
    }

    @Test
    fun `未设置且无默认值的 select 返回 Invalid`() {
        val result = RunPlanBuilder.build(
            definition,
            configWith(
                ConfiguredTask(
                    taskName = "常规作战",
                    enabled = true,
                    // 自定义作战关卡为 switch 无 default_case，保持 Unset
                    optionValues = emptyMap(),
                ),
            ),
        )
        assertTrue("Unset 无默认值应 Invalid: $result", result is RunPlanResult.Invalid)
        val diagnostics = (result as RunPlanResult.Invalid).diagnostics
        assertTrue(
            diagnostics.any {
                it.message.isResource(R.string.diagnostic_option_unset_without_default, "自定义作战关卡")
            },
        )
    }

    @Test
    fun `不适用任务被过滤`() {
        // 切换账号仅适用于官服；切到 B 服后应被过滤
        val id = RunConfigurationId("test")
        val config = UserConfiguration(
            initialized = true,
            activeResourceName = "B 服",
            configurations = listOf(
                RunConfiguration(id, "测试", listOf(ConfiguredTask("切换账号", enabled = true))),
            ),
            activeConfigurationId = id,
        )
        val result = RunPlanBuilder.build(definition, config)
        assertTrue(result is RunPlanResult.NoExecutableTasks)
    }

    @Test
    fun `resolver 与 builder 对同一 Unset 语义一致`() {
        // Resolver 对 Unset 无默认值的 select 不隐式选第一项
        val session = ConfigurationResolver.resolve(
            definition,
            configWith(ConfiguredTask("常规作战", enabled = true)),
        )
        val task = session.activeConfiguration!!.tasks.single()
        val switch = task.options.first { it.name == "自定义作战关卡" }
        assertTrue("Unset 无默认值不应有活动 case", switch.activeCases.isEmpty())
    }

    /** 自定义关卡 3-9 的常规作战任务；其余 option 选定避免级联出 Unset 诊断 */
    private fun customStageTask() = ConfiguredTask(
        taskName = "常规作战",
        enabled = true,
        optionValues = mapOf(
            "自定义作战关卡" to OptionValue.SingleCase("Yes"),
            "作战关卡(自定义)" to OptionValue.Inputs(mapOf("章节号" to "3", "关卡号" to "9")),
            "主线关卡难度" to firstCaseOf("主线关卡难度"),
            "吃糖" to firstCaseOf("吃糖"),
            "自定义作战次数" to firstCaseOf("自定义作战次数"),
            "掉落统计上报" to firstCaseOf("掉落统计上报"),
        ),
    )

    /** 选择无子 option 的 case，避免测试再级联出 Unset 诊断 */
    private fun firstCaseOf(optionName: String): OptionValue.SingleCase {
        val option = definition.options.getValue(optionName)
        check(option is OptionDefinition.Choice) { "非 choice option: $optionName" }
        val case = option.cases.firstOrNull { it.childOptionNames.isEmpty() } ?: option.cases.first()
        return OptionValue.SingleCase(case.name)
    }

    /** 提取 SelectCombatStage.action.param.custom_action_param.stage */
    private fun JsonObject.stageValue(): String? = runCatching {
        this["SelectCombatStage"]!!.jsonObject["action"]!!.jsonObject["param"]!!
            .jsonObject["custom_action_param"]!!.jsonObject["stage"]!!.jsonPrimitive.content
    }.getOrNull()

    /** 提取 CombatStageGate.recognition.param.custom_recognition_param.expression */
    private fun JsonObject.expressionValue(): String? = runCatching {
        this["CombatStageGate"]!!.jsonObject["recognition"]!!.jsonObject["param"]!!
            .jsonObject["custom_recognition_param"]!!.jsonObject["expression"]!!.jsonPrimitive.content
    }.getOrNull()
}
