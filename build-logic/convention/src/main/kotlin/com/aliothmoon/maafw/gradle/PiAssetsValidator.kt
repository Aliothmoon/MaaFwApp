package com.aliothmoon.maafw.gradle

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Lenient JSONC reader matching PiParser on the app side: upstream PIs routinely use comments and
 * trailing commas in interface.json
 */
@OptIn(ExperimentalSerializationApi::class)
private val piInterfaceJson = Json {
    ignoreUnknownKeys = true
    allowComments = true
    allowTrailingComma = true
}

/**
 * Checks that every resource directory Android can pass to `MaaResourcePostBundle` actually made it
 * into the synced PI tree with at least one file
 *
 * A profile that narrows `include` away from `resource_*&#47;**` (to keep desktop-only overlays out of
 * the APK) otherwise ships a clean build whose missing directory only surfaces on a device when the
 * user selects that resource or Adb controller and `prepare()` aborts. Empty directories count as
 * missing too because `packPiArchive` sets `includeEmptyDirs = false`
 */
internal fun verifySyncedPiAssets(piRoot: File): List<String> {
    val interfaceFile = File(piRoot, "interface.json")
    if (!interfaceFile.isFile) {
        return listOf("interface.json is missing")
    }

    val root = try {
        piInterfaceJson.parseToJsonElement(interfaceFile.readText()).jsonObject
    } catch (e: Exception) {
        return listOf("interface.json is not a valid JSON object: ${e.message}")
    }

    val problems = mutableListOf<String>()

    // Android only drives Adb controllers (see ProjectLoader.resolveControllers); Win32/Linux/MacOS/PlayCover
    // overlays and resources scoped exclusively to non-Adb controllers are expected to be excluded by profiles
    // that trim desktop assets
    val controllers = (root["controller"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val adbControllers = controllers.filter { it.string("type")?.equals("Adb", ignoreCase = true) == true }
    val adbControllerNames = adbControllers.mapNotNull { it.string("name") }.toSet()

    val resources = (root["resource"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    if (resources.isNotEmpty()) {
        resources.forEachIndexed { index, obj ->
            val supportedControllers = obj.stringList("controller")
            if (supportedControllers.isNotEmpty() && controllers.isNotEmpty() &&
                supportedControllers.none { it in adbControllerNames }
            ) {
                return@forEachIndexed
            }
            val name = obj.string("name") ?: "#$index"
            obj.stringList("path").forEach { rawPath ->
                checkResourceDir(piRoot, rawPath)?.let { reason ->
                    problems += """resource "$name": path "${rawPath.removePrefix("./")}" $reason"""
                }
            }
        }
    } else {
        // ProjectLoader.deriveResources falls back to subdirectories of resource/ when resource[] is omitted
        checkResourceDir(piRoot, "resource")?.let { reason ->
            problems += """resource/ $reason (interface.json declares no resource[])"""
        }
    }

    adbControllers.forEachIndexed { index, obj ->
        val name = obj.string("name") ?: "#$index"
        obj.stringList("attach_resource_path").forEach { rawPath ->
            checkResourceDir(piRoot, rawPath)?.let { reason ->
                problems += """controller "$name" (Adb): attach_resource_path "${rawPath.removePrefix("./")}" $reason"""
            }
        }
    }

    return problems
}

private fun checkResourceDir(piRoot: File, rawPath: String): String? {
    val normalized = rawPath.removePrefix("./")
    if (normalized.isBlank() || File(normalized).isAbsolute) {
        return "is not a relative path inside the PI root"
    }
    val rootNorm = piRoot.toPath().toAbsolutePath().normalize()
    val targetNorm = rootNorm.resolve(normalized).normalize()
    if (!targetNorm.startsWith(rootNorm) || targetNorm == rootNorm) {
        return "escapes the PI root"
    }
    val dir = targetNorm.toFile()
    if (!dir.isDirectory) {
        return "is missing"
    }
    if (dir.walkTopDown().none { it.isFile }) {
        return "contains no files"
    }
    return null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> value.contentOrNull?.let(::listOf).orEmpty()
    else -> emptyList()
}
