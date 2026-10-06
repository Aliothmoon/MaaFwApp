package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.passwordFields
import com.aliothmoon.maafw.config.withPasswordFieldsMarked
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.domain.casesOrEmpty
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.ScheduleType
import kotlinx.serialization.json.JsonElement
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** MXU 配置转换后的结果：确认前就算好，确认只是落盘 */
data class MxuImport(
    /** 每个 MXU 标签页一份，追加在现有配置后面 */
    val configurations: List<RunConfiguration>,
    val globalOptionValues: Map<String, OptionValue>,
    val resourceOptionValues: Map<String, Map<String, OptionValue>>,
    val controllerOptionValues: Map<String, Map<String, OptionValue>>,
    /** 一律先关着，用户确认过时间再打开 */
    val schedules: List<ScheduleStrategy>,
    val skipped: MxuSkipped,
) {
    /**
     * 追加配置；全局、资源、控制器选项按键覆盖。本机已经填了的 password 以本机为准，不被导入的值顶掉或清空
     */
    fun applyTo(current: UserConfiguration, definition: ProjectDefinition): UserConfiguration {
        fun merge(
            existing: Map<String, Map<String, OptionValue>>,
            incoming: Map<String, Map<String, OptionValue>>,
        ) = existing + incoming.mapValues { (bucket, values) -> existing[bucket].orEmpty() + values }

        val activeExists = current.configurations.any { it.id == current.activeConfigurationId }
        return current.copy(
            // 还没按 preset 初始化就导入的话，别让初始化回头把导入的配置整个换掉
            initialized = true,
            configurations = current.configurations + configurations,
            activeConfigurationId = if (activeExists) current.activeConfigurationId else configurations.firstOrNull()?.id,
            globalOptionValues = current.globalOptionValues + globalOptionValues,
            resourceOptionValues = merge(current.resourceOptionValues, resourceOptionValues),
            controllerOptionValues = merge(current.controllerOptionValues, controllerOptionValues),
        ).withPasswordFieldsMarked(definition).withSecretsKeptFrom(current)
    }
}

/** 导不过来、被丢掉的东西，预览里逐类列给用户看 */
data class MxuSkipped(
    /** PI 里没有的任务名，去重 */
    val unknownTasks: List<String> = emptyList(),
    /** MXU 自带的特殊任务（等待、通知、关机等），Android 上不跑 */
    val mxuTasks: Int = 0,
    /** MXU 的前置任务、前置动作：都是在电脑上启动外部程序 */
    val preActions: Int = 0,
    /** 选项值对不上 PI（选项没了、case 没了、类型不符、hotkey） */
    val options: Int = 0,
    /** 没有星期或时刻的定时，MXU 里本来就不会触发 */
    val schedules: Int = 0,
)

/**
 * MXU 配置 → FwApp 配置
 *
 * 选项值照 MXU 加载时的清洗规则来（src/stores/appStore.ts importConfig）：PI 里没有的选项、没有的 case、
 * 类型对不上的值都丢掉，没值的走默认。和 FwApp 自己的导入不同——那边的值本来就是 FwApp 写的，原样保留
 *
 * MXU 的资源、控制器选项存在每个任务的 optionValues 里，FwApp 按资源、控制器分桶；取第一个出现的值
 */
internal class MxuConfigMapper(
    private val definition: ProjectDefinition,
    /** FwApp 当前的 controller；任务按 MXU 记下的这个 controller 的勾选状态导入 */
    private val controllerName: String,
    /** MXU 标签页的资源在 PI 里找不到时，资源选项落到这个桶 */
    private val fallbackResourceName: String?,
    private val newConfigurationId: () -> RunConfigurationId = ConfigurationResolver::newConfigurationId,
) {
    private val resourceOptionNames = definition.optionClosure(definition.resources.flatMap { it.optionNames })
    private val controllerOptionNames = definition.optionClosure(definition.controllers.flatMap { it.optionNames })
    private val passwordFields = definition.passwordFields()

    private var droppedOptions = 0

    fun map(instances: List<MxuInstance>, globalValues: Map<String, JsonElement>): MxuImport {
        droppedOptions = 0
        val unknownTasks = LinkedHashSet<String>()
        var mxuTasks = 0
        var preActions = 0
        var skippedSchedules = 0
        val resourceValues = LinkedHashMap<String, MutableMap<String, OptionValue>>()
        val controllerValues = LinkedHashMap<String, OptionValue>()
        val schedules = mutableListOf<ScheduleStrategy>()

        val configurations = instances.map { instance ->
            preActions += instance.preActions.size + (if (instance.preAction != null) 1 else 0)
            val resourceBucket = instance.resourceName?.takeIf { name -> definition.resources.any { it.name == name } }
                ?: fallbackResourceName
            val tasks = instance.tasks.mapNotNull { mxuTask ->
                val name = mxuTask.taskName
                val task = definition.task(name)
                when {
                    name.startsWith(PRETASK_PREFIX) -> { preActions++; null }
                    name.startsWith(MXU_TASK_PREFIX) -> { mxuTasks++; null }
                    task == null -> { unknownTasks += name; null }
                    else -> {
                        val taskOptions = definition.optionClosure(task.optionNames)
                        val values = LinkedHashMap<String, OptionValue>()
                        mxuTask.optionValues.forEach { (optionName, raw) ->
                            when (optionName) {
                                in taskOptions -> convert(optionName, raw)?.let { values[optionName] = it }
                                in resourceOptionNames -> if (resourceBucket != null) {
                                    convert(optionName, raw)?.let {
                                        resourceValues.getOrPut(resourceBucket, ::LinkedHashMap).putIfAbsent(optionName, it)
                                    }
                                }
                                in controllerOptionNames -> convert(optionName, raw)?.let {
                                    controllerValues.putIfAbsent(optionName, it)
                                }
                                // 和这个任务无关的旧值：MXU 自己也不会再用，不算丢
                                in definition.options -> Unit
                                else -> droppedOptions++
                            }
                        }
                        val checked = mxuTask.enabledByController?.get(controllerName) ?: mxuTask.enabled
                        ConfiguredTask(
                            taskName = name,
                            // Android 上跑不了的任务照收，但不勾（与「从模板创建」一致）
                            enabled = checked && ConfigurationResolver.isControllerSupported(definition, task),
                            optionValues = values,
                            customLabel = mxuTask.customName?.trim()?.takeUnless(String::isBlank),
                        )
                    }
                }
            }
            val configuration = RunConfiguration(
                id = newConfigurationId(),
                name = instance.name.trim().ifBlank { DEFAULT_NAME },
                tasks = tasks,
            )
            instance.schedulePolicies.forEach { policy ->
                val strategy = policy.toStrategy(configuration)
                if (strategy == null) skippedSchedules++ else schedules += strategy
            }
            configuration
        }

        val globalOptions = definition.optionClosure(definition.globalOptionNames)
        val global = LinkedHashMap<String, OptionValue>()
        globalValues.forEach { (optionName, raw) ->
            if (optionName in globalOptions) convert(optionName, raw)?.let { global[optionName] = it }
            else if (optionName !in definition.options) droppedOptions++
        }

        return MxuImport(
            configurations = configurations,
            globalOptionValues = global,
            resourceOptionValues = resourceValues,
            controllerOptionValues = if (controllerValues.isEmpty()) emptyMap() else mapOf(controllerName to controllerValues),
            schedules = schedules,
            skipped = MxuSkipped(
                unknownTasks = unknownTasks.toList(),
                mxuTasks = mxuTasks,
                preActions = preActions,
                options = droppedOptions,
                schedules = skippedSchedules,
            ),
        )
    }

    /** 对不上 PI 的值返回 null 并计数；password 字段解开 MXU 的混淆存成明文，落盘时再走 FwApp 自己的加密 */
    private fun convert(optionName: String, raw: JsonElement): OptionValue? {
        val value = MxuOptionValue.parse(raw)
        val converted = when (val option = definition.options[optionName]) {
            null -> null
            is OptionDefinition.Choice -> when (value) {
                is MxuOptionValue.Select -> value.caseName.takeIf { name -> option.cases.any { it.name == name } }
                is MxuOptionValue.Switch -> option.cases.firstOrNull { it.name in if (value.value) YES_CASES else NO_CASES }?.name
                else -> null
            }?.let(OptionValue::SingleCase)

            is OptionDefinition.Checkbox -> (value as? MxuOptionValue.Checkbox)?.let { checkbox ->
                val valid = checkbox.caseNames.filter { name -> option.cases.any { it.name == name } }.distinct()
                // 全部失效就丢；本来就是空的是「明确不选」，照收
                if (valid.isEmpty() && checkbox.caseNames.isNotEmpty()) null else OptionValue.MultipleCases(valid)
            }

            is OptionDefinition.Input -> (value as? MxuOptionValue.Input)?.let { input ->
                val passwords = passwordFields[optionName].orEmpty()
                val values = option.fields.mapNotNull { field ->
                    val plain = if (field.name in passwords) {
                        input.encryptedValues[field.name]?.let { encrypted ->
                            MxuFormat.decryptSecret(encrypted, MxuFormat.inputSecretKey(definition.name, optionName, field.name))
                        } ?: input.values[field.name]
                    } else {
                        input.values[field.name]
                    }
                    plain?.takeUnless { field.name in passwords && it.isEmpty() }?.let { field.name to it }
                }.toMap()
                OptionValue.Inputs(values, secretFields = passwords)
            }
        }
        if (converted == null) droppedOptions++
        return converted
    }

    private fun MxuSchedulePolicy.toStrategy(configuration: RunConfiguration): ScheduleStrategy? {
        val days = weekdays.mapNotNull { day ->
            when (day) {
                0 -> DayOfWeek.SUNDAY
                in 1..6 -> DayOfWeek.of(day)
                else -> null
            }
        }.toSet()
        val clock = times.mapNotNull { runCatching { LocalTime.parse(it.trim(), TIME) }.getOrNull() }
            .ifEmpty { hours.filter { it in 0..23 }.map { LocalTime.of(it, 0) } }
            .distinct()
            .sorted()
        if (days.isEmpty() || clock.isEmpty()) return null
        return ScheduleStrategy(
            name = listOf(configuration.name, name.trim()).filter(String::isNotEmpty).joinToString(" - "),
            enabled = false,
            scheduleType = ScheduleType.FIXED_TIME,
            runConfigurationId = configuration.id.value,
            daysOfWeek = days,
            executionTimes = clock,
        )
    }

    private companion object {
        const val PRETASK_PREFIX = "__MXU_PRETASK__"
        const val MXU_TASK_PREFIX = "__MXU_"
        const val DEFAULT_NAME = "MXU"

        /** 与 MXU src/utils/optionHelpers.ts 的 switch 对照表一致 */
        val YES_CASES = setOf("Yes", "yes", "Y", "y")
        val NO_CASES = setOf("No", "no", "N", "n")

        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}

/** 从 [roots] 出发，连同各 case 的子选项一路展开；MXU 把子选项和父选项平铺在同一张表里 */
private fun ProjectDefinition.optionClosure(roots: Iterable<String>): Set<String> {
    val seen = LinkedHashSet<String>()
    val pending = ArrayDeque(roots.toList())
    while (pending.isNotEmpty()) {
        val name = pending.removeLast()
        if (!seen.add(name)) continue
        options[name]?.casesOrEmpty()?.forEach { pending.addAll(it.childOptionNames) }
    }
    return seen
}
