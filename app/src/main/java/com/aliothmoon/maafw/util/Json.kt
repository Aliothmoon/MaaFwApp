package com.aliothmoon.maafw.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val JSON = Json {
    ignoreUnknownKeys = true
}

/** 解析失败返回 null，不抛异常 */
fun parseJsonObject(body: String): JsonObject? =
    parseJsonElement(body) as? JsonObject

fun parseJsonArray(body: String): JsonArray? =
    parseJsonElement(body) as? JsonArray

private fun parseJsonElement(body: String): JsonElement? = try {
    JSON.parseToJsonElement(body)
} catch (_: Exception) {
    null
}

/** 空白字符串视为缺失 */
fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
