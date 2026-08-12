package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PI v2.5.0 约定 Client 拉起 agent 子进程时注入的 `PI_*`
 *
 * 语义对齐 MXU 的 `src/utils/piEnv.ts`：条目整条透传、`$` 前缀递归查表、拿不到就不设那一项
 * （不是设空串——agent 侧普遍用「变量存在与否」判断 Client 是否支持这套约定）
 *
 * `PI_CLIENT_MAAFW_VERSION` 不在这里：MaaFramework 版本要问 native，只有特权进程侧拿得到，
 * 由 ExecAgentHost 落地前补上
 */
object PiAgentEnv {

    /** 按哪一版协议注入这些变量；不表示外壳实现了 v2.5.0 的全部内容 */
    private const val INTERFACE_VERSION = "v2.5.0"

    private const val CLIENT_NAME = "MaaFwApp"

    private val compact = Json { encodeDefaults = true }

    fun build(
        projectVersion: String?,
        controller: ControllerDefinition,
        resource: ResourceDefinition,
        translations: Map<String, String>,
        clientVersion: String?,
        clientLanguage: String?,
    ): Map<String, String> = buildMap {
        put("PI_INTERFACE_VERSION", INTERFACE_VERSION)
        put("PI_CLIENT_NAME", CLIENT_NAME)
        clientVersion?.takeIf(String::isNotBlank)?.let { put("PI_CLIENT_VERSION", it) }
        clientLanguage?.takeIf(String::isNotBlank)?.let { put("PI_CLIENT_LANGUAGE", it) }
        projectVersion?.takeIf(String::isNotBlank)?.let { put("PI_VERSION", it) }
        controller.raw.takeIf { it.isNotEmpty() }?.let {
            put("PI_CONTROLLER", it.resolveI18n(translations).toCompactJson())
        }
        resource.raw.takeIf { it.isNotEmpty() }?.let {
            put("PI_RESOURCE", it.resolveI18n(translations).toCompactJson())
        }
    }

    private fun JsonElement.toCompactJson(): String = compact.encodeToString(JsonElement.serializer(), this)

    /** 整条递归：label/description 之外的字符串同样可能是 `$key`，与 MXU 一致不做字段白名单 */
    private fun JsonElement.resolveI18n(translations: Map<String, String>): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.resolveI18n(translations) })
        is JsonArray -> JsonArray(map { it.resolveI18n(translations) })
        is JsonPrimitive -> if (isString) JsonPrimitive(content.resolveKey(translations)) else this
    }

    /** 只认 `$` 前缀；查不到退回 key 本身而不是留着 `$`，与 MXU 的 `translations?.[key] ?? key` 一致 */
    private fun String.resolveKey(translations: Map<String, String>): String =
        if (startsWith('$')) substring(1).let { translations[it] ?: it } else this
}
