package com.aliothmoon.maafw.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val UPDATE_JSON = Json {
    ignoreUnknownKeys = true
}

internal fun parseJsonObject(body: String): JsonObject? =
    parseJsonElement(body) as? JsonObject

internal fun parseJsonArray(body: String): JsonArray? =
    parseJsonElement(body) as? JsonArray

private fun parseJsonElement(body: String): JsonElement? = try {
    UPDATE_JSON.parseToJsonElement(body)
} catch (_: Exception) {
    null
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun String.isApkUrl(): Boolean =
    substringBefore('#').substringBefore('?').endsWith(".apk", ignoreCase = true)
