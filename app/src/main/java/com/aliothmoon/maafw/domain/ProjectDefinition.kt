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
    val templates: List<ConfigurationTemplate>,
    /** 顶层 agent 声明，按 PI 里的顺序；无 agent 的 PI 为空 */
    val agents: List<AgentDefinition> = emptyList(),
) {
    fun task(taskName: String): TaskDefinition? = taskIndex[taskName]

    private val taskIndex: Map<String, TaskDefinition> by lazy { tasks.associateBy { it.name } }
}

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
)

data class ResourceDefinition(
    val name: String,
    val paths: List<String>,
    /** $i18n 已物化；匹配/持久化仍用 [name] */
    val label: String = name,
)

/**
 * PI 顶层 agent 声明；单对象与数组两种形态在解析期都归一成列表
 *
 * [childExec] 在 Android 上不解释也不执行：设备上 PATH 里没有解释器，PI 解包目录又是 noexec
 * 实际拉起哪个可执行体由构建期的 agent 运行时描述决定，这里只留作诊断与计数
 * PI 的 `identifier` 不投影——上游 MaaPiCli 解析进 RuntimeParam 之后同样没有消费者
 * （`Runner.cpp` 恒以 nullptr 建 client），见 docs/pi-compatibility.md
 */
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

sealed interface OptionDefinition {
    val name: String
    val label: String
    val description: String?

    /** Select/Switch 共享 cases + defaultCase */
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
