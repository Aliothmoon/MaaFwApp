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
)

sealed interface OptionDefinition {
    val name: String
    val label: String
    val description: String?

    data class Select(
        override val name: String,
        override val label: String,
        override val description: String?,
        val cases: List<OptionCaseDefinition>,
        val defaultCase: String?,
    ) : OptionDefinition

    data class Switch(
        override val name: String,
        override val label: String,
        override val description: String?,
        val cases: List<OptionCaseDefinition>,
        val defaultCase: String?,
    ) : OptionDefinition

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
)

/** PI preset 的领域名称：只读的一次性创建模板。 */
data class ConfigurationTemplate(
    val name: String,
    val description: String?,
    val tasks: List<TemplateTask>,
)

data class TemplateTask(
    val taskName: String,
    val enabled: Boolean,
    val optionValues: Map<String, OptionValue>,
)
