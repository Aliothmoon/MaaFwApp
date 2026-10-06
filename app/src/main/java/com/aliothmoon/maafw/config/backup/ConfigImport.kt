package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.util.int
import com.aliothmoon.maafw.util.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 解析好、等用户确认的一次导入 */
sealed interface ConfigImport {

    /** FwApp 自己的备份：整体覆盖 */
    data class Restore(val backup: ConfigBackup) : ConfigImport

    /** MXU 配置文件或分享码：追加 */
    data class FromMxu(val source: MxuSource, val result: MxuImport) : ConfigImport

    enum class MxuSource { ConfigFile, ShareCode }
}

sealed interface ConfigImportError {
    /** 认不出是哪种格式，或内容坏了 */
    data object Unrecognized : ConfigImportError

    /** 来自别的项目；[source] 是文件或分享码里写的项目名 */
    data class ProjectMismatch(val source: String, val current: String) : ConfigImportError

    /** 更新版本的 FwApp 导出的备份 */
    data object NewerFormat : ConfigImportError

    /** MXU 分享码的版本不认识 */
    data object UnsupportedShareCode : ConfigImportError

    /** 认出来了，但对当前 PI 一份配置都转不出来 */
    data object Empty : ConfigImportError
}

internal sealed interface ConfigImportParse {
    data class Parsed(val import: ConfigImport) : ConfigImportParse
    data class Failed(val error: ConfigImportError) : ConfigImportParse
}

/**
 * 一个入口认三种东西：FwApp 备份（JSON，format 字段）、MXU 配置文件（mxu-*.json）、
 * MXU 分享码（剪贴板或它导出的 txt）。按内容认，不靠扩展名：SAF 给的 MIME 和文件名都不可靠
 */
internal object ConfigImportParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        text: String,
        fileName: String?,
        definition: ProjectDefinition,
        current: UserConfiguration,
    ): ConfigImportParse {
        val trimmed = text.removePrefix(BOM).trim()
        // JSON 先认：配置里的任务别名之类碰巧带着分享码的标记，也不该被当成分享码
        if (!trimmed.startsWith("{") && MxuShareCodes.looksLikeShareCode(trimmed)) {
            return parseShareCode(trimmed, definition, current)
        }
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return failed(ConfigImportError.Unrecognized)
        return when {
            root.string("format") == ConfigBackup.FORMAT -> parseBackup(root, trimmed, definition)
            "instances" in root -> parseMxuConfig(trimmed, fileName, definition, current)
            else -> failed(ConfigImportError.Unrecognized)
        }
    }

    private fun parseBackup(root: JsonObject, text: String, definition: ProjectDefinition): ConfigImportParse {
        val version = root.int("formatVersion") ?: return failed(ConfigImportError.Unrecognized)
        if (version > ConfigBackup.FORMAT_VERSION) return failed(ConfigImportError.NewerFormat)
        val backup = runCatching { ConfigBackupCodec.decode(text) }.getOrNull()
            ?: return failed(ConfigImportError.Unrecognized)
        if (backup.project.name != definition.name) {
            return failed(ConfigImportError.ProjectMismatch(backup.project.name, definition.name))
        }
        return ConfigImportParse.Parsed(ConfigImport.Restore(backup))
    }

    private fun parseMxuConfig(
        text: String,
        fileName: String?,
        definition: ProjectDefinition,
        current: UserConfiguration,
    ): ConfigImportParse {
        // 配置文件里不记项目名，只有文件名带着；改过名就认不出，交给下面「一份都转不出来」兜
        MxuFormat.mismatchedProjectName(fileName, definition.name)?.let { name ->
            return failed(ConfigImportError.ProjectMismatch(name, definition.name))
        }
        val config = runCatching { MxuFormat.decodeConfig(text) }.getOrNull()
            ?: return failed(ConfigImportError.Unrecognized)
        return mapped(ConfigImport.MxuSource.ConfigFile, config.instances, config.globalOptionValues, definition, current)
    }

    private fun parseShareCode(text: String, definition: ProjectDefinition, current: UserConfiguration): ConfigImportParse =
        when (val decoded = MxuShareCodes.decode(text)) {
            MxuShareCodeResult.Invalid -> failed(ConfigImportError.Unrecognized)
            MxuShareCodeResult.UnsupportedVersion -> failed(ConfigImportError.UnsupportedShareCode)
            is MxuShareCodeResult.Decoded ->
                // 与 MXU 一致：分享码的项目名对不上就拒
                if (decoded.code.projectName != definition.name) {
                    failed(ConfigImportError.ProjectMismatch(decoded.code.projectName, definition.name))
                } else {
                    mapped(ConfigImport.MxuSource.ShareCode, listOf(decoded.code.instance), emptyMap(), definition, current)
                }
        }

    private fun mapped(
        source: ConfigImport.MxuSource,
        instances: List<MxuInstance>,
        globalValues: Map<String, JsonElement>,
        definition: ProjectDefinition,
        current: UserConfiguration,
    ): ConfigImportParse {
        val result = MxuConfigMapper(
            definition = definition,
            controllerName = definition.controller(current.activeControllerName).name,
            fallbackResourceName = current.activeResourceName ?: definition.resources.firstOrNull()?.name,
        ).map(instances, globalValues)
        // 一个已知任务都没有，多半是别的项目的文件被改了名
        if (result.configurations.none { it.tasks.isNotEmpty() } && result.globalOptionValues.isEmpty()) {
            return failed(ConfigImportError.Empty)
        }
        return ConfigImportParse.Parsed(ConfigImport.FromMxu(source, result))
    }

    private fun failed(error: ConfigImportError) = ConfigImportParse.Failed(error)

    private val BOM = Char(0xFEFF).toString()
}
