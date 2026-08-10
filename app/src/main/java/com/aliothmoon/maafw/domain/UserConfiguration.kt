package com.aliothmoon.maafw.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RunConfigurationId(val value: String)

enum class ThemeMode { System, Light, Dark }

/** 持久化聚合根：只存用户选择，不复制 PI；schemaVersion 在序列化层 */
@Serializable
data class UserConfiguration(
    val initialized: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
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

/** 名称可重复；定位一律用 id */
@Serializable
data class RunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val tasks: List<ConfiguredTask> = emptyList(),
)

/** 保留任务设置，配置与任务实例换新身份 */
fun RunConfiguration.duplicate(
    id: RunConfigurationId,
    name: String,
): RunConfiguration = RunConfiguration(
    id = id,
    name = name,
    tasks = tasks.map { it.copy(instanceId = newTaskInstanceId()) },
)

/**
 * 同配置内可重复 taskName；定位用 instanceId
 * 旧数据缺 instanceId 时解码补默认值，首次写回后固定
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
 * Unset = value map 无该键
 * MultipleCases(emptyList()) = 明确不选，二者不可混用
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
