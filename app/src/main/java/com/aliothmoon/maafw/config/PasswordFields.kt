package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.UserConfiguration

/** 各作用域的 option 值统一走这一处，新增作用域时 password 的标记、加密、收集才不会漏掉一处 */
internal fun UserConfiguration.mapOptionValues(transform: (String, OptionValue) -> OptionValue): UserConfiguration {
    fun Map<String, OptionValue>.mapped() = mapValues { (name, value) -> transform(name, value) }
    return copy(
        globalOptionValues = globalOptionValues.mapped(),
        controllerOptionValues = controllerOptionValues.mapValues { it.value.mapped() },
        resourceOptionValues = resourceOptionValues.mapValues { it.value.mapped() },
        configurations = configurations.map { configuration ->
            configuration.copy(
                tasks = configuration.tasks.map { it.copy(optionValues = it.optionValues.mapped()) },
            )
        },
    )
}

private fun UserConfiguration.allOptionValues(): Sequence<OptionValue> = sequence {
    yieldAll(globalOptionValues.values)
    controllerOptionValues.values.forEach { yieldAll(it.values) }
    resourceOptionValues.values.forEach { yieldAll(it.values) }
    configurations.forEach { configuration -> configuration.tasks.forEach { yieldAll(it.optionValues.values) } }
}

/** option 名 → 其中的 password 字段名；没有 password 字段的 option 不出现 */
fun ProjectDefinition.passwordFields(): Map<String, Set<String>> =
    options.mapNotNull { (name, option) ->
        val fields = (option as? OptionDefinition.Input)?.fields
            ?.filter { it.password }
            ?.mapTo(mutableSetOf()) { it.name }
        fields?.takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap()

/** 补上 PI 定义里的 password 标记；已有标记不撤 */
fun OptionValue.withSecretFields(fields: Set<String>?): OptionValue =
    if (this is OptionValue.Inputs && fields != null && !secretFields.containsAll(fields)) {
        copy(secretFields = secretFields + fields)
    } else {
        this
    }

/**
 * 按 PI 定义给所有 Inputs 补 password 标记；没有要补的返回原对象。
 * 覆盖 PI 更新后才把某个字段改成 password 的旧配置：标上之后下一次落盘就加密
 */
fun UserConfiguration.withPasswordFieldsMarked(definition: ProjectDefinition): UserConfiguration {
    val fields = definition.passwordFields()
    if (fields.isEmpty()) return this
    val marked = mapOptionValues { name, value -> value.withSecretFields(fields[name]) }
    return if (marked == this) this else marked
}

/**
 * 落盘前：标了 password 的字段从 values 挪进 sealed 并加密。
 * 加密失败的值宁可丢掉也不落明文，读回来就是没填
 */
internal fun UserConfiguration.withSecretsSealed(seal: (String) -> String?): UserConfiguration =
    mapOptionValues { _, value ->
        val inputs = value as? OptionValue.Inputs
        if (inputs == null || inputs.secretFields.isEmpty()) return@mapOptionValues value
        val sealed = inputs.secretFields.mapNotNull { field ->
            inputs.values[field]?.takeIf(String::isNotEmpty)?.let(seal)?.let { field to it }
        }.toMap()
        inputs.copy(values = inputs.values - inputs.secretFields, sealed = sealed)
    }

/** 读回后：解密进 values；解不开的（密钥没了）当作没填 */
internal fun UserConfiguration.withSecretsOpened(open: (String) -> String?): UserConfiguration =
    mapOptionValues { _, value ->
        val inputs = value as? OptionValue.Inputs
        if (inputs == null || inputs.sealed.isEmpty()) return@mapOptionValues value
        val opened = inputs.sealed.mapNotNull { (field, sealed) -> open(sealed)?.let { field to it } }.toMap()
        inputs.copy(values = inputs.values + opened, sealed = emptyMap())
    }

/** 日志导出打码用：已标记 password 的字段当前的明文 */
fun UserConfiguration.passwordPlaintexts(): Set<String> =
    allOptionValues()
        .filterIsInstance<OptionValue.Inputs>()
        .flatMap { inputs -> inputs.secretFields.mapNotNull { inputs.values[it] } }
        .filter(String::isNotEmpty)
        .toSet()
