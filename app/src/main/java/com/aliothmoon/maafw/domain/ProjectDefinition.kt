package com.aliothmoon.maafw.domain

import kotlinx.serialization.json.JsonObject

/** PI 加载合并后的不可变声明；不含用户状态 */
data class ProjectDefinition(
    val name: String,
    val version: String?,
    val controller: ControllerDefinition,
    val resources: List<ResourceDefinition>,
    val tasks: List<TaskDefinition>,
    val groups: List<TaskGroupDefinition>,
    val options: Map<String, OptionDefinition>,
    /** PI v2.3.0 `global_option[]`：参与每个任务的 override，优先级最低，且不依赖任何选择 */
    val globalOptionNames: List<String> = emptyList(),
    val templates: List<ConfigurationTemplate>,
    /** 顶层 agent 声明，按 PI 里的顺序；无 agent 的 PI 为空 */
    val agents: List<AgentDefinition> = emptyList(),
    val metadata: ProjectMetadata = ProjectMetadata(),
    /** null = PI 没声明 telemetry，或声明了但 dsn 为空 */
    val telemetry: TelemetryDefinition? = null,
    /**
     * 当前语言的 `$key` 查表原样保留一份
     *
     * 声明层的 label/description 在加载期就物化完了，本不需要它；留着是为了 pipeline 的
     * `focus` 模板——那是运行期才随回调到达的正文，查表只能推迟到那时候
     */
    val translations: Map<String, String> = emptyMap(),
) {
    fun task(taskName: String): TaskDefinition? = taskIndex[taskName]

    private val taskIndex: Map<String, TaskDefinition> by lazy { tasks.associateBy { it.name } }
}

/** PI v2.9.0 `telemetry.sentry`；[dsn] 由 PI 提供，外壳自己没有上报去处 */
data class TelemetryDefinition(
    val dsn: String,
    val tracing: Boolean = true,
    val tracesSampleRate: Double = 1.0,
    val environment: String? = null,
)

/**
 * [welcomeFingerprint] 算在物化前的原始声明上：算在正文上的话，切一次语言换了译文就会重弹
 */
data class ProjectMetadata(
    val welcome: String? = null,
    val welcomeFingerprint: String? = null,
    val description: String? = null,
    val contact: String? = null,
    val license: String? = null,
    val github: String? = null,
)

/**
 * PI 声明的 controller 投影
 * 设备上 type 为 Adb 的项由 Android native controller 实现，见 docs/pi-compatibility.md
 */
data class ControllerDefinition(
    val name: String = "Android",
    val type: String = "ADB",
    /** 三者互斥，都缺省时由 Runner 按默认分辨率兜底 */
    val displayShortSide: Int? = null,
    val displayLongSide: Int? = null,
    val displayRaw: Boolean = false,
    /**
     * PI 里这一条的原样对象，供 `PI_CONTROLLER` 整条透传（见 PiAgentEnv）
     * 投影只留外壳用得上的字段，而协议要求交给 agent 的是完整条目；空对象表示该条不是 PI 声明的
     */
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class ResourceDefinition(
    val name: String,
    val paths: List<String>,
    /** $i18n 已物化；匹配/持久化仍用 [name] */
    val label: String = name,
    /** 同 [ControllerDefinition.raw]，供 `PI_RESOURCE` 透传 */
    val raw: JsonObject = JsonObject(emptyMap()),
    val icon: String? = null,
    /** PI v2.3.0 `resource[].option`：当前选中这份时参与每个任务的 override */
    val optionNames: List<String> = emptyList(),
)

data class AgentDefinition(
    val childExec: String,
    val childArgs: List<String>,
)

data class TaskDefinition(
    val name: String,
    val entry: String,
    /** $i18n 已物化；缺省回落 name */
    val label: String = name,
    val description: String?,
    val groups: List<String>,
    val optionNames: List<String>,
    val pipelineOverride: JsonObject,
    /** 空 = 全部 controller / resource 适用 */
    val controllers: List<String>,
    val resources: List<String>,
    val defaultCheck: Boolean,
    val icon: String? = null,
)

/** PI v2.4.0 顶层 group[]；label 缺省回落 name */
data class TaskGroupDefinition(
    val name: String,
    val label: String = name,
    val description: String? = null,
    val icon: String? = null,
    val defaultExpand: Boolean = true,
    /** 加载器合成的未分组兜底；用标记判定，避免与真实同名 group 冲突 */
    val isUngrouped: Boolean = false,
)

/**
 * option 的适用范围（PI v2.3.0 的 `controller` / `resource`）；空列表 = 不限
 *
 * v2.3.1 起这是硬约束而不只是展示提示：不满足时该 option **连同其子 option** 都不产生
 * pipeline_override；协议允许客户端隐藏或灰显，本项目选隐藏（docs/domain-model.md §6.3），
 * Resolver 与 Builder 各判一次同一条件；已保存的值不删，环境切回来就重新露面
 */
data class OptionApplicability(
    val controllers: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
) {
    fun matches(controllerName: String, resourceName: String?): Boolean =
        (controllers.isEmpty() || controllerName in controllers) &&
                (resources.isEmpty() || resourceName in resources)

    companion object {
        val Unrestricted = OptionApplicability()
    }
}

sealed interface OptionDefinition {
    val name: String
    val label: String
    val description: String?
    val icon: String?
    val applicability: OptionApplicability

    /** Select/Switch 共享 cases + defaultCase */
    sealed interface Choice : OptionDefinition {
        val cases: List<OptionCaseDefinition>
        val defaultCase: String?

        /**
         * 未设值时的落点：PI 的 `default_case`，缺了退到首个 case
         * 生态里绝大多数 option 不写 `default_case`，逼用户逐个选不现实；与 MXU 的 `default_case || cases[0]` 同语义
         * 只有 cases 为空才是 null——那是 PI 自己写坏，留给编译期报诊断
         * Resolver 与 RunPlanBuilder 必须共用这一处：两边算法不同就会「显示的」与「跑的」分叉
         */
        val effectiveDefaultCase: String? get() = defaultCase ?: cases.firstOrNull()?.name
    }

    data class Select(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : Choice

    data class Switch(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : Choice

    data class Checkbox(
        override val name: String,
        override val label: String,
        override val description: String?,
        val cases: List<OptionCaseDefinition>,
        val defaultCases: List<String>,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : OptionDefinition

    data class Input(
        override val name: String,
        override val label: String,
        override val description: String?,
        val fields: List<InputFieldDefinition>,
        val pipelineOverride: JsonObject,
        override val icon: String? = null,
        override val applicability: OptionApplicability = OptionApplicability.Unrestricted,
    ) : OptionDefinition
}

/** Input 无 cases，返回 empty */
fun OptionDefinition.casesOrEmpty(): List<OptionCaseDefinition> = when (this) {
    is OptionDefinition.Choice -> cases
    is OptionDefinition.Checkbox -> cases
    is OptionDefinition.Input -> emptyList()
}

data class OptionCaseDefinition(
    val name: String,
    val label: String,
    val description: String?,
    val pipelineOverride: JsonObject,
    val childOptionNames: List<String>,
    val icon: String? = null,
)

enum class PipelineType { StringType, IntType, BoolType }

data class InputFieldDefinition(
    val name: String,
    val pipelineType: PipelineType,
    val default: String,
    /** 编译失败的 regex 在 load 期报诊断；此处为已编译结果 */
    val verify: Regex?,
    val patternMessage: String?,
    val description: String?,
    /** $i18n 已物化；placeholder 仍用 [name] */
    val label: String = name,
)

/** PI preset 一次性模板；name 标识，label 展示 */
data class ConfigurationTemplate(
    val name: String,
    val label: String,
    val description: String?,
    val tasks: List<TemplateTask>,
    val icon: String? = null,
) {
    /** 同名任务保留先声明的一条；resolver 与 UI 必须一致 */
    val distinctTasks: List<TemplateTask> get() = tasks.distinctBy { it.taskName }
}

data class TemplateTask(
    val taskName: String,
    val enabled: Boolean,
    val optionValues: Map<String, OptionValue>,
    /** 加载期由任务定义回填；缺定义回落 taskName */
    val label: String = taskName,
)
