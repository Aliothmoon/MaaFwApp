package com.aliothmoon.maafw.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RunConfigurationId(val value: String)

enum class ThemeMode { System, Light, Dark }

/**
 * 用户配置的唯一事实来源（持久化聚合根）。
 * 只保存用户选择，不复制 PI 定义；schemaVersion 信封在序列化层处理。
 */
@Serializable
data class UserConfiguration(
    val initialized: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val developerMode: Boolean = false,
    val activeResourceName: String? = null,
    val globalOptionValues: Map<String, OptionValue> = emptyMap(),
    val controllerOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    val resourceOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    val configurations: List<RunConfiguration> = emptyList(),
    val activeConfigurationId: RunConfigurationId? = null,
    val welcomeFingerprint: String? = null,
) {
    fun configuration(id: RunConfigurationId?): RunConfiguration? =
        id?.let { target -> configurations.firstOrNull { it.id == target } }
}

/** 用户可编辑的一份运行配置；名称允许重复，定位一律使用 id。 */
@Serializable
data class RunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val tasks: List<ConfiguredTask> = emptyList(),
)

/** 复制为独立配置：保留任务设置，但为配置和任务实例换用新的身份。 */
fun RunConfiguration.duplicate(
    id: RunConfigurationId,
    name: String,
): RunConfiguration = RunConfiguration(
    id = id,
    name = name,
    tasks = tasks.map { it.copy(instanceId = newTaskInstanceId()) },
)

/**
 * 同一 RunConfiguration 内允许重复 taskName（每次添加生成独立实例，
 * 各自持有 enabled / optionValues）；实例定位一律使用 instanceId。
 * 旧持久化数据缺失 instanceId 时由默认值在解码时补齐，首次写回后固定。
 */
@Serializable
data class ConfiguredTask(
    val taskName: String,
    val enabled: Boolean = true,
    val optionValues: Map<String, OptionValue> = emptyMap(),
    val instanceId: String = newTaskInstanceId(),
)

fun newTaskInstanceId(): String = java.util.UUID.randomUUID().toString()

/**
 * 用户对某个 option 的当前选择。Unset（从未配置）表示为 value map 中不存在该键，
 * 与 MultipleCases(emptyList())（明确不选）必须区分。
 */
@Serializable
sealed interface OptionValue {

    @Serializable
    @SerialName("single")
    data class SingleCase(val case: String) : OptionValue

    @Serializable
    @SerialName("multiple")
    data class MultipleCases(val cases: List<String>) : OptionValue

    @Serializable
    @SerialName("inputs")
    data class Inputs(val values: Map<String, String>) : OptionValue
}
