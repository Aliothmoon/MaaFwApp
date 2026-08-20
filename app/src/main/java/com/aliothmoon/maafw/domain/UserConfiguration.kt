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
    val announcementFingerprint: String? = null,
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
 * 复制任务实例：新 instanceId，继承 taskName/启用/选项；customLabel 由调用方算好（含去重）传入，插在源后
 */
fun RunConfiguration.duplicateTask(taskInstanceId: String, customLabel: String): RunConfiguration {
    val tasks = tasks.toMutableList()
    val index = tasks.indexOfFirst { it.instanceId == taskInstanceId }
    if (index < 0) return this
    val source = tasks[index]
    tasks.add(index + 1, source.copy(instanceId = newTaskInstanceId(), customLabel = customLabel))
    return copy(tasks = tasks)
}

/**
 * 重命名任务：设显示别名；空白/null 清除回退定义 label。规范 taskName 不动，pipeline 不受影响
 */
fun RunConfiguration.renameTask(taskInstanceId: String, customLabel: String?): RunConfiguration =
    copy(tasks = tasks.map {
        if (it.instanceId == taskInstanceId) {
            it.copy(customLabel = customLabel?.trim()?.takeUnless(String::isBlank))
        } else it
    })

/**
 * 算复制任务的展示名：剥掉源名末尾的「copySuffix」或「copySuffix N」得根名，再加 [copySuffix]；
 * 与 [existing] 撞名则追加「 2/3/…」。UI 传当前各任务的展示名，避免「(副本) (副本)」
 */
fun uniqueCopyLabel(sourceLabel: String, copySuffix: String, existing: Collection<String>): String {
    val root = sourceLabel.replace(Regex(Regex.escape(copySuffix) + "( \\d+)?$"), "")
    val base = root + copySuffix
    if (base !in existing) return base
    var n = 2
    while ("$base $n" in existing) n++
    return "$base $n"
}

/**
 * 同配置内可重复 taskName；定位用 instanceId
 * 旧数据缺 instanceId 时解码补默认值，首次写回后固定
 */
@Serializable
data class ConfiguredTask(
    val taskName: String,
    val enabled: Boolean = true,
    val optionValues: Map<String, OptionValue> = emptyMap(),
    /** 显示别名；null = 用定义 label。重命名与「(副本)」后缀都写这里，规范 taskName 不动 */
    val customLabel: String? = null,
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
