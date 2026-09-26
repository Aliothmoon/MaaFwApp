package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.config.UserConfigurationSerializer
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.settings.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** 导出日志附带的可读快照；非调试导出时凭据只保留「是否已设置」这一层信息 */
object ExportSnapshots {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun settings(settings: AppSettings, redactSecrets: Boolean = true): String = buildJsonObject {
        put("snapshotVersion", 1)
        put("startupBackend", settings.startupBackend)
        put("skipShizukuCheck", settings.skipShizukuCheck)
        put("shizukuLaunchPackage", settings.shizukuLaunchPackage)
        put("shizukuShortcutEnabled", settings.shizukuShortcutEnabled)
        put("runMode", settings.runMode)
        put("overlayControlMode", settings.overlayControlMode)
        put("screenSaverEnabled", settings.screenSaverEnabled)
        put("closeAppAfterTask", settings.closeAppAfterTask)
        put("touchPreviewEnabled", settings.touchPreviewEnabled)
        put("resolutionPreference", settings.resolutionPreference)
        put("debugMode", settings.debugMode)
        put("themeStyle", settings.themeStyle)
        put("eventNotificationLevel", settings.eventNotificationLevel)
        put("wakeUnlockEnabled", settings.wakeUnlockEnabled)
        put(
            "wakeCredential",
            settings.wakeCredential.takeUnless { redactSecrets } ?: redact(settings.wakeCredential),
        )
        put("telemetryEnabled", settings.telemetryEnabled)
        put("autoCheckUpdate", settings.autoCheckUpdate)
        put("autoDownloadUpdate", settings.autoDownloadUpdate)
        put("updateChannel", settings.updateChannel)
        put("updateSource", settings.updateSource)
        put("pipOnHome", settings.pipOnHome)
        put(
            "mirrorchyanCdk",
            settings.mirrorchyanCdk.takeUnless { redactSecrets } ?: redact(settings.mirrorchyanCdk),
        )
    }.toString()

    fun piConfig(
        config: UserConfiguration,
        definition: ProjectDefinition? = null,
        redactSecrets: Boolean = true,
    ): String = buildJsonObject {
        put("snapshotVersion", 1)
        put("schemaVersion", UserConfigurationSerializer.SCHEMA_VERSION)
        put(
            "config",
            if (redactSecrets) {
                redactSensitive(
                    json.encodeToJsonElement(
                        UserConfiguration.serializer(),
                        config.redactDeclaredPasswords(definition.declaredPasswordFields()),
                    ),
                )
            } else {
                json.encodeToJsonElement(UserConfiguration.serializer(), config)
            },
        )
    }.toString()

    /** v2.10 的密码标记不再依赖字段名；名字启发式只做旧数据/坏数据的兜底 */
    private fun UserConfiguration.redactDeclaredPasswords(
        passwordFields: Map<String, Set<String>>,
    ): UserConfiguration = copy(
        globalOptionValues = redactInputPasswords(globalOptionValues, passwordFields),
        controllerOptionValues = controllerOptionValues.mapValues { (_, values) ->
            redactInputPasswords(values, passwordFields)
        },
        resourceOptionValues = resourceOptionValues.mapValues { (_, values) ->
            redactInputPasswords(values, passwordFields)
        },
        configurations = configurations.map { configuration ->
            configuration.copy(
                tasks = configuration.tasks.map { task ->
                    task.copy(optionValues = redactInputPasswords(task.optionValues, passwordFields))
                },
            )
        },
    )

    private fun redactInputPasswords(
        values: Map<String, OptionValue>,
        passwordFields: Map<String, Set<String>>,
    ): Map<String, OptionValue> = values.mapValues { (optionName, value) ->
        val secrets = passwordFields[optionName].orEmpty()
        if (secrets.isEmpty()) return@mapValues value
        (value as? OptionValue.Inputs)?.takeIf { inputs -> inputs.values.keys.any(secrets::contains) }
            ?.let { inputs ->
                inputs.copy(
                    values = inputs.values.mapValues { (fieldName, fieldValue) ->
                        if (fieldName in secrets) redact(fieldValue) else fieldValue
                    },
                )
            }
            ?: value
    }

    private fun ProjectDefinition?.declaredPasswordFields(): Map<String, Set<String>> =
        this?.options?.values?.mapNotNull { option ->
            if (option !is OptionDefinition.Input) return@mapNotNull null
            val names = option.fields.filter { it.password }.mapTo(mutableSetOf()) { it.name }
            names.ifEmpty { null }?.let { option.name to it }
        }?.toMap().orEmpty()

    private fun redact(value: String): String =
        if (value.isBlank()) "" else "[redacted]"

    private fun redactSensitive(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(
                    key,
                    if (isSensitiveKey(key) && containsNotBlankString(value)) {
                        JsonPrimitive("[redacted]")
                    } else {
                        redactSensitive(value)
                    },
                )
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { add(redactSensitive(it)) }
        }

        else -> element
    }

    private fun containsNotBlankString(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.isString && element.content.isNotBlank()
        is JsonObject -> element.values.any(::containsNotBlankString)
        is JsonArray -> element.any(::containsNotBlankString)
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return sensitiveKeyNames.any(normalized::contains)
    }

    private val sensitiveKeyNames = listOf(
        "credential",
        "password",
        "passwd",
        "passphrase",
        "token",
        "secret",
        "apikey",
        "privatekey",
        "cdk",
    )
}
