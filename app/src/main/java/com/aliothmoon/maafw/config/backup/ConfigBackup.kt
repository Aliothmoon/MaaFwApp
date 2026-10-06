package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.withPasswordFieldsMarked
import com.aliothmoon.maafw.config.withSecretsSealed
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.domain.newTaskInstanceId
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * FwApp 自己的配置备份文件
 *
 * 发出去的字段得一直认：加字段给缺省值，改语义才升 [FORMAT_VERSION]。
 * password 选项只留字段名，CDK、解锁 PIN、手势、推送凭据整个不进文件
 */
@Serializable
data class ConfigBackup(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: Long,
    val app: BackupAppInfo,
    val project: BackupProjectInfo,
    val configuration: BackupConfiguration,
    /** 运行时字段（上次触发、结果）已清掉 */
    val schedules: List<ScheduleStrategy> = emptyList(),
    /** AppSettings 里可跨设备的那几项；键是 DataStore 的键名，见 AppSettingsManager.portableSettings */
    val appSettings: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "maafwapp-config"
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class BackupAppInfo(val applicationId: String, val versionName: String)

@Serializable
data class BackupProjectInfo(val name: String, val version: String? = null)

/** [UserConfiguration] 里属于用户选择的部分；initialized、公告指纹这些本机状态不导 */
@Serializable
data class BackupConfiguration(
    val themeMode: ThemeMode = ThemeMode.System,
    val activeResourceName: String? = null,
    val activeControllerName: String? = null,
    val globalOptionValues: Map<String, OptionValue> = emptyMap(),
    val controllerOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    val resourceOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    val configurations: List<RunConfiguration> = emptyList(),
    val activeConfigurationId: RunConfigurationId? = null,
)

internal object ConfigBackupCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        // 「加字段不升版本」的另一半：新版本给枚举加了值，旧版本读到的那一项回落默认，而不是整份认不出
        coerceInputValues = true
    }

    fun encode(backup: ConfigBackup): String = json.encodeToString(backup)

    fun decode(text: String): ConfigBackup = json.decodeFromString(text)

    fun export(
        config: UserConfiguration,
        definition: ProjectDefinition,
        schedules: List<ScheduleStrategy>,
        appSettings: Map<String, String>,
        app: BackupAppInfo,
        exportedAt: Long,
    ): ConfigBackup {
        // 先按 PI 补一遍标记：加载时的迁移还没跑到的旧值也别以明文出去；
        // 再按落盘的路子挪走 password 字段，只是不给密文，文件里只剩标记
        val stripped = config.withPasswordFieldsMarked(definition).withSecretsSealed { null }
        return ConfigBackup(
            exportedAt = exportedAt,
            app = app,
            project = BackupProjectInfo(definition.name, definition.version),
            configuration = BackupConfiguration(
                themeMode = stripped.themeMode,
                activeResourceName = stripped.activeResourceName,
                activeControllerName = stripped.activeControllerName,
                globalOptionValues = stripped.globalOptionValues,
                controllerOptionValues = stripped.controllerOptionValues,
                resourceOptionValues = stripped.resourceOptionValues,
                configurations = stripped.configurations,
                activeConfigurationId = stripped.activeConfigurationId,
            ),
            schedules = schedules.map { it.withoutRunState() },
            appSettings = appSettings,
        )
    }

    /**
     * 整体覆盖成备份里的样子
     *
     * 备份不带 password 的值，本机已经填好的照旧留着，不因为一次导入被清空。
     * 手改过的文件可能破坏 §10 的不变式，这里顺手修：重复的配置 id、任务实例 id 换新，
     * 指向不存在的激活配置回落第一份
     */
    fun restore(backup: ConfigBackup, current: UserConfiguration, definition: ProjectDefinition): UserConfiguration {
        val source = backup.configuration
        val configIds = HashSet<RunConfigurationId>()
        val instanceIds = HashSet<String>()
        val configurations = source.configurations.map { configuration ->
            configuration.copy(
                id = configIds.claim(configuration.id, ConfigurationResolver::newConfigurationId),
                tasks = configuration.tasks.map { task ->
                    task.copy(instanceId = instanceIds.claim(task.instanceId, ::newTaskInstanceId))
                },
            )
        }
        val restored = current.copy(
            initialized = true,
            themeMode = source.themeMode,
            activeResourceName = source.activeResourceName,
            activeControllerName = source.activeControllerName,
            globalOptionValues = source.globalOptionValues,
            controllerOptionValues = source.controllerOptionValues,
            resourceOptionValues = source.resourceOptionValues,
            configurations = configurations,
            activeConfigurationId = source.activeConfigurationId?.takeIf { it in configIds }
                ?: configurations.firstOrNull()?.id,
        ).withPasswordFieldsMarked(definition)
        return restored.withSecretsKeptFrom(current)
    }

    /**
     * 备份里的定时整批换上：重复 id 换新；绑的配置不在备份里就解绑并关掉，
     * 免得到点去跑一份不存在的配置
     */
    fun restoreSchedules(backup: ConfigBackup, configurationIds: Set<RunConfigurationId>): List<ScheduleStrategy> {
        val ids = HashSet<String>()
        return backup.schedules.map { strategy ->
            val bound = RunConfigurationId(strategy.runConfigurationId) in configurationIds
            strategy.withoutRunState().copy(
                id = ids.claim(strategy.id) { UUID.randomUUID().toString() },
                runConfigurationId = if (bound) strategy.runConfigurationId else "",
                enabled = strategy.enabled && bound,
            )
        }
    }

    /** 上次触发、结果是这台设备的运行记录，不进文件也不随恢复带回来 */
    private fun ScheduleStrategy.withoutRunState() =
        copy(lastTriggeredAt = null, lastResult = null, lastResultMessage = null)

    /** 没占用过就沿用 [id]，重复了换 [fresh] 给的新 id */
    private fun <T> MutableSet<T>.claim(id: T, fresh: () -> T): T = if (add(id)) id else fresh().also(::add)
}

/**
 * 本机已经填了的 password 以本机为准：导入不覆盖、不清空，只补本机空着的字段
 *
 * 导出时本来就不带这些值，覆盖回来要是跟着清掉，等于每导一次就得重填一遍；
 * MXU 导入倒是带着解开的密码，但本机填过的仍不让它顶掉
 */
internal fun OptionValue.withSecretsKeptFrom(existing: OptionValue?): OptionValue {
    val filled = existing.filledSecrets()
    if (this !is OptionValue.Inputs || filled.isEmpty()) return this
    return copy(values = values + filled, secretFields = secretFields + filled.keys)
}

/** 新出现的任务实例借同名任务里填过的 password：只补空着的，导入值自己带了就用导入的 */
private fun OptionValue.withEmptySecretsFilledFrom(other: OptionValue?): OptionValue {
    val filled = other.filledSecrets()
    if (this !is OptionValue.Inputs || filled.isEmpty()) return this
    val missing = filled.filterKeys { values[it].isNullOrEmpty() }
    return copy(values = values + missing, secretFields = secretFields + filled.keys)
}

private fun OptionValue?.filledSecrets(): Map<String, String> {
    if (this !is OptionValue.Inputs) return emptyMap()
    return secretFields.mapNotNull { field -> values[field]?.takeIf(String::isNotEmpty)?.let { field to it } }.toMap()
}

/**
 * 整份配置按作用域对回 [previous] 保住本机的 password：全局按选项名，资源、控制器按桶名 + 选项名，
 * 导入里压根没有的选项也把本机填过的 password 带过来（其余字段回默认）。
 * 任务按实例 id 对（同一台设备导出又导回来）；对不上的是新实例，只从同名任务补空着的
 */
internal fun UserConfiguration.withSecretsKeptFrom(previous: UserConfiguration): UserConfiguration {
    val previousTasks = previous.configurations.flatMap { it.tasks }
    val byInstance = previousTasks.associateBy { it.instanceId }

    fun Map<String, OptionValue>.kept(previousValues: Map<String, OptionValue>?): Map<String, OptionValue> {
        val carried = previousValues.orEmpty().filterKeys { it !in this }.mapNotNull { (name, value) ->
            value.filledSecrets().takeIf { it.isNotEmpty() }
                ?.let { name to OptionValue.Inputs(values = it, secretFields = it.keys) }
        }
        return mapValues { (name, value) -> value.withSecretsKeptFrom(previousValues?.get(name)) } + carried
    }

    fun Map<String, Map<String, OptionValue>>.keptBuckets(previousBuckets: Map<String, Map<String, OptionValue>>) =
        (keys + previousBuckets.keys)
            .associateWith { bucket -> this[bucket].orEmpty().kept(previousBuckets[bucket]) }
            .filter { (bucket, values) -> bucket in this || values.isNotEmpty() }

    return copy(
        globalOptionValues = globalOptionValues.kept(previous.globalOptionValues),
        controllerOptionValues = controllerOptionValues.keptBuckets(previous.controllerOptionValues),
        resourceOptionValues = resourceOptionValues.keptBuckets(previous.resourceOptionValues),
        configurations = configurations.map { configuration ->
            configuration.copy(
                tasks = configuration.tasks.map { task ->
                    val sameInstance = byInstance[task.instanceId]?.takeIf { it.taskName == task.taskName }
                    task.copy(
                        optionValues = task.optionValues.mapValues { (name, value) ->
                            if (sameInstance != null) {
                                value.withSecretsKeptFrom(sameInstance.optionValues[name])
                            } else {
                                value.withEmptySecretsFilledFrom(
                                    previousTasks.firstNotNullOfOrNull { other ->
                                        other.optionValues[name]
                                            ?.takeIf { other.taskName == task.taskName && it.filledSecrets().isNotEmpty() }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        },
    )
}
