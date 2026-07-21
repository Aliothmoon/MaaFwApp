package com.aliothmoon.maafw.domain

import kotlinx.serialization.json.JsonObject

/**
 * ProjectInterface 经过加载、合并与校验后的不可变声明。
 * 属于项目声明，不属于用户状态；用户不直接修改。
 */
data class ProjectDefinition(
    val name: String,
    val version: String?,
    val controller: ControllerDefinition,
    val resources: List<ResourceDefinition>,
    val tasks: List<TaskDefinition>,
    val groups: List<TaskGroupDefinition>,
    val options: Map<String, OptionDefinition>,
    val templates: List<ConfigurationTemplate>,
) {
    fun task(taskName: String): TaskDefinition? = taskIndex[taskName]

    private val taskIndex: Map<String, TaskDefinition> by lazy { tasks.associateBy { it.name } }
}

/** 本应用使用固定的 Android 专用 controller，无需用户选择。 */
data class ControllerDefinition(
    val name: String = "Android",
    val type: String = "ADB",
)

data class ResourceDefinition(
    val name: String,
    val paths: List<String>,
    /** 展示名（$i18n 已物化）；内部匹配与持久化仍使用 [name]。 */
    val label: String = name,
)

data class TaskDefinition(
    val name: String,
    val entry: String,
    /** 显示名（$i18n 已物化）；缺省回落 name。 */
    val label: String = name,
    val description: String?,
    val groups: List<String>,
    val optionNames: List<String>,
    val pipelineOverride: JsonObject,
    /** 为空表示对全部 controller / resource 适用。 */
    val controllers: List<String>,
    val resources: List<String>,
    val defaultCheck: Boolean,
)

/** PI v2.4.0 顶层 group[] 声明；label 缺省回落 name。 */
data class TaskGroupDefinition(
    val name: String,
    val label: String = name,
    val description: String? = null,
    val icon: String? = null,
    val defaultExpand: Boolean = true,
    /** 加载器合成的「未分组」兜底组；按此标记（而非组名）判定，避免与真实同名 group 冲突。 */
    val isUngrouped: Boolean = false,
)

sealed interface OptionDefinition {
    val name: String
    val label: String
    val description: String?

    /** 单选语义（Select/Switch 共享 cases + defaultCase），供 resolver/builder 统一分派。 */
    sealed interface Choice : OptionDefinition {
        val cases: List<OptionCaseDefinition>
        val defaultCase: String?
    }

    data class Select(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
    ) : Choice

    data class Switch(
        override val name: String,
        override val label: String,
        override val description: String?,
        override val cases: List<OptionCaseDefinition>,
        override val defaultCase: String?,
    ) : Choice

    data class Checkbox(
        override val name: String,
        override val label: String,
        override val description: String?,
        val cases: List<OptionCaseDefinition>,
        val defaultCases: List<String>,
    ) : OptionDefinition

    data class Input(
        override val name: String,
        override val label: String,
        override val description: String?,
        val fields: List<InputFieldDefinition>,
        val pipelineOverride: JsonObject,
    ) : OptionDefinition
}

/** 全 option 类型统一取 cases（Input 无 cases）。 */
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
)

enum class PipelineType { StringType, IntType, BoolType }

data class InputFieldDefinition(
    val name: String,
    val pipelineType: PipelineType,
    val default: String,
    /** 编译失败的 regex 在 Project load 阶段报诊断，此处保存已编译结果。 */
    val verify: Regex?,
    val patternMessage: String?,
    val description: String?,
    /** 展示名（$i18n 已物化）；pipeline placeholder 仍使用 [name]。 */
    val label: String = name,
)

/** PI preset 的领域名称：只读的一次性创建模板。name 为标识符，label 为展示名。 */
data class ConfigurationTemplate(
    val name: String,
    val label: String,
    val description: String?,
    val tasks: List<TemplateTask>,
) {
    /** 创建/预览共用的去重投影：同名任务保留先声明的一条（resolver 与 UI 必须一致）。 */
    val distinctTasks: List<TemplateTask> get() = tasks.distinctBy { it.taskName }
}

data class TemplateTask(
    val taskName: String,
    val enabled: Boolean,
    val optionValues: Map<String, OptionValue>,
    /** 展示名（加载期由任务定义回填物化）；任务定义缺失时回落 taskName。 */
    val label: String = taskName,
)
