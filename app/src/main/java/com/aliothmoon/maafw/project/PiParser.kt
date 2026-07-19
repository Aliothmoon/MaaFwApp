package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.TemplateTask
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 单个 PI 分片文件（task[] / option{} / preset[] / group[]）的解析结果。 */
data class PiFileContent(
    val tasks: List<TaskDefinition> = emptyList(),
    val options: Map<String, OptionDefinition> = emptyMap(),
    val templates: List<ConfigurationTemplate> = emptyList(),
    val groups: List<TaskGroupDefinition> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** PI 根 interface.json 中当前领域模型需要的项目元数据。 */
data class PiInterfaceContent(
    val name: String?,
    val version: String?,
    val resources: List<ResourceDefinition>,
    val groups: List<TaskGroupDefinition>,
    /** v2 languages 声明：语言 tag -> 翻译文件相对路径。 */
    val languages: Map<String, String>,
    val diagnostics: List<Diagnostic>,
)

/**
 * 解析期文本物化钩子（对齐 MXU contentResolver 语义）：
 * label 级字段只做 $i18n 查表；description 级字段追加文件形态读取。
 * URL 形态一律原样保留，由 UI 层懒加载。
 */
interface PiTextResolver {
    fun label(raw: String?): String?
    fun description(raw: String?): String?

    object None : PiTextResolver {
        override fun label(raw: String?): String? = raw
        override fun description(raw: String?): String? = raw
    }
}

/**
 * PI V2 分片文件解析器。宽容解析：字段级错误降级为诊断并跳过该条目，
 * 不让单个坏条目阻断整个项目加载。
 */
object PiParser {

    // PI 生态普遍使用 JSONC（注释 + 尾逗号），与 MXU 的 parseJsonc 对齐
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    fun parseInterface(source: String, content: String): PiInterfaceContent {
        val diagnostics = mutableListOf<Diagnostic>()
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            diagnostics += error(source, "JSON 解析失败: ${e.message}")
            return PiInterfaceContent(null, null, emptyList(), emptyList(), emptyMap(), diagnostics)
        }

        val resources = (root["resource"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also { diagnostics += error(source, "resource 条目不是对象") }
            val name = obj.string("name")
                ?: return@mapNotNull null.also { diagnostics += error(source, "resource 缺少 name") }
            val paths = obj.stringList("path").map(::normalizeProjectPath)
            if (paths.isEmpty()) {
                diagnostics += error(source, "resource \"$name\" 缺少 path")
                null
            } else {
                ResourceDefinition(name, paths)
            }
        }

        val languages = buildMap {
            (root["languages"] as? JsonObject)?.forEach { (lang, element) ->
                (element as? JsonPrimitive)?.contentOrNull?.let { put(lang, it) }
                    ?: run { diagnostics += warning(source, "languages \"$lang\" 的路径不是字符串") }
            }
        }

        return PiInterfaceContent(
            name = root.string("name"),
            version = root.string("version"),
            resources = resources,
            // 根文件的 group 解析时翻译表尚未加载，$i18n 由 ProjectLoader 后置物化
            groups = parseGroups(source, root, diagnostics),
            languages = languages,
            diagnostics = diagnostics,
        )
    }

    fun parseFile(source: String, content: String, text: PiTextResolver = PiTextResolver.None): PiFileContent {
        val diagnostics = mutableListOf<Diagnostic>()
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            diagnostics += error(source, "JSON 解析失败: ${e.message}")
            return PiFileContent(diagnostics = diagnostics)
        }

        val tasks = (root["task"] as? JsonArray).orEmpty().mapNotNull { element ->
            parseTask(source, element, diagnostics, text)
        }
        val options = buildMap {
            (root["option"] as? JsonObject)?.forEach { (name, element) ->
                parseOption(source, name, element, diagnostics, text)?.let { put(name, it) }
            }
        }
        val templates = (root["preset"] as? JsonArray).orEmpty().mapNotNull { element ->
            parsePreset(source, element, diagnostics, text)
        }
        val groups = parseGroups(source, root, diagnostics, text)
        return PiFileContent(tasks, options, templates, groups, diagnostics)
    }

    /** v2.4.0 顶层 group[] 声明：根 interface.json 与 import 分片均可出现。 */
    private fun parseGroups(
        source: String,
        root: JsonObject,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver = PiTextResolver.None,
    ): List<TaskGroupDefinition> =
        (root["group"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also { diagnostics += error(source, "group 条目不是对象") }
            val name = obj.string("name")
                ?: return@mapNotNull null.also { diagnostics += error(source, "group 缺少 name") }
            TaskGroupDefinition(
                name = name,
                label = text.label(obj.string("label")) ?: name,
                description = text.description(obj.string("description")),
                icon = obj.string("icon"),
                defaultExpand = obj.boolean("default_expand") ?: true,
            )
        }

    private fun parseTask(
        source: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): TaskDefinition? {
        val obj = element as? JsonObject
            ?: return null.also { diagnostics += error(source, "task 条目不是对象") }
        val name = obj.string("name")
            ?: return null.also { diagnostics += error(source, "task 缺少 name") }
        val entry = obj.string("entry")
            ?: return null.also { diagnostics += error(source, "task \"$name\" 缺少 entry") }
        return TaskDefinition(
            name = name,
            entry = entry,
            description = text.description(obj.string("description") ?: obj.string("desc")),
            groups = obj.stringList("group"),
            optionNames = obj.stringList("option"),
            pipelineOverride = obj.objectOrEmpty("pipeline_override"),
            controllers = obj.stringList("controller"),
            resources = obj.stringList("resource"),
            defaultCheck = obj.boolean("check") ?: false,
        )
    }

    private fun parseOption(
        source: String,
        name: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): OptionDefinition? {
        val obj = element as? JsonObject
            ?: return null.also { diagnostics += error(source, "option \"$name\" 不是对象") }
        val label = text.label(obj.string("label")) ?: name
        val description = text.description(obj.string("description"))
        return when (val type = obj.string("type")) {
            "select", "switch" -> {
                val cases = parseCases(source, name, obj, diagnostics, text)
                val defaultCase = obj.string("default_case")?.also {
                    if (cases.none { c -> c.name == it }) {
                        diagnostics += warning(source, "option \"$name\" 的 default_case \"$it\" 不在 cases 中")
                    }
                }?.takeIf { d -> cases.any { it.name == d } }
                if (type == "select") {
                    OptionDefinition.Select(name, label, description, cases, defaultCase)
                } else {
                    OptionDefinition.Switch(name, label, description, cases, defaultCase)
                }
            }

            "checkbox" -> {
                val cases = parseCases(source, name, obj, diagnostics, text)
                val defaults = when (val d = obj["default_case"]) {
                    null -> emptyList()
                    is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.content }
                    is JsonPrimitive -> listOf(d.content)
                    else -> emptyList()
                }.filter { d ->
                    cases.any { it.name == d }.also { found ->
                        if (!found) diagnostics += warning(source, "option \"$name\" 的 default_case \"$d\" 不在 cases 中")
                    }
                }
                OptionDefinition.Checkbox(name, label, description, cases, defaults)
            }

            "input" -> {
                val fields = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { field ->
                    parseInputField(source, name, field, diagnostics, text)
                }
                if (fields.isEmpty()) {
                    diagnostics += warning(source, "input option \"$name\" 没有可用的 inputs")
                }
                OptionDefinition.Input(name, label, description, fields, obj.objectOrEmpty("pipeline_override"))
            }

            // MXU 合法类型，但热键是桌面端语义，Android 端跳过不投影
            "hotkey" -> {
                diagnostics += warning(source, "option \"$name\" 的 type \"hotkey\" 在 Android 端不支持，已跳过")
                null
            }

            else -> {
                diagnostics += error(source, "option \"$name\" 的 type 非法: $type")
                null
            }
        }
    }

    private fun parseCases(
        source: String,
        optionName: String,
        obj: JsonObject,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): List<OptionCaseDefinition> =
        (obj["cases"] as? JsonArray).orEmpty().mapNotNull { element ->
            val case = element as? JsonObject
                ?: return@mapNotNull null.also { diagnostics += error(source, "option \"$optionName\" 存在非对象 case") }
            val caseName = case.string("name")
                ?: return@mapNotNull null.also { diagnostics += error(source, "option \"$optionName\" 存在缺少 name 的 case") }
            OptionCaseDefinition(
                name = caseName,
                label = text.label(case.string("label")) ?: caseName,
                description = text.description(case.string("description")),
                pipelineOverride = case.objectOrEmpty("pipeline_override"),
                childOptionNames = case.stringList("option"),
            )
        }

    private fun parseInputField(
        source: String,
        optionName: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): InputFieldDefinition? {
        val obj = element as? JsonObject
            ?: return null.also { diagnostics += error(source, "input option \"$optionName\" 存在非对象 field") }
        val name = obj.string("name")
            ?: return null.also { diagnostics += error(source, "input option \"$optionName\" 存在缺少 name 的 field") }
        val pipelineType = when (val t = obj.string("pipeline_type")?.lowercase()) {
            null, "string" -> PipelineType.StringType
            "int" -> PipelineType.IntType
            "bool" -> PipelineType.BoolType
            else -> {
                diagnostics += warning(source, "input \"$optionName.$name\" 的 pipeline_type 非法: $t，按 string 处理")
                PipelineType.StringType
            }
        }
        val verifyPattern = obj.string("verify")
        val verify = verifyPattern?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                diagnostics += error(source, "input \"$optionName.$name\" 的 verify regex 编译失败: ${e.message}")
                null
            }
        }
        val default = when (val d = obj["default"]) {
            null -> ""
            is JsonPrimitive -> d.content
            else -> ""
        }
        return InputFieldDefinition(
            name = name,
            pipelineType = pipelineType,
            default = default,
            verify = verify,
            patternMessage = text.label(obj.string("pattern_msg")),
            description = text.description(obj.string("description")),
        )
    }

    private fun parsePreset(
        source: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): ConfigurationTemplate? {
        val obj = element as? JsonObject
            ?: return null.also { diagnostics += error(source, "preset 条目不是对象") }
        val name = obj.string("name")
            ?: return null.also { diagnostics += error(source, "preset 缺少 name") }
        val tasks = (obj["task"] as? JsonArray).orEmpty().mapNotNull { taskElement ->
            val task = taskElement as? JsonObject
                ?: return@mapNotNull null.also { diagnostics += error(source, "preset \"$name\" 存在非对象 task") }
            val taskName = task.string("name")
                ?: return@mapNotNull null.also { diagnostics += error(source, "preset \"$name\" 存在缺少 name 的 task") }
            TemplateTask(
                taskName = taskName,
                enabled = task.boolean("enabled") ?: true,
                optionValues = parsePresetOptionValues(task["option"]),
            )
        }
        return ConfigurationTemplate(name, text.description(obj.string("description")), tasks)
    }

    /** preset 的 option 值形态：string -> SingleCase，array -> MultipleCases，object -> Inputs。 */
    private fun parsePresetOptionValues(element: JsonElement?): Map<String, OptionValue> {
        val obj = element as? JsonObject ?: return emptyMap()
        return buildMap {
            obj.forEach { (optionName, value) ->
                when (value) {
                    is JsonPrimitive -> put(optionName, OptionValue.SingleCase(value.content))
                    is JsonArray -> put(
                        optionName,
                        OptionValue.MultipleCases(value.mapNotNull { (it as? JsonPrimitive)?.content }),
                    )

                    is JsonObject -> put(
                        optionName,
                        OptionValue.Inputs(
                            value.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap(),
                        ),
                    )

                    else -> Unit
                }
            }
        }
    }

    private fun error(source: String, message: String) =
        Diagnostic(DiagnosticSeverity.Error, source, message)

    private fun warning(source: String, message: String) =
        Diagnostic(DiagnosticSeverity.Warning, source, message)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(value.content)
        else -> emptyList()
    }

    private fun JsonObject.objectOrEmpty(key: String): JsonObject =
        (this[key] as? JsonObject) ?: JsonObject(emptyMap())

    /** 翻译文件：扁平的 key -> 译文 JSON 对象。 */
    fun parseTranslations(
        source: String,
        content: String,
        onDiagnostic: (Diagnostic) -> Unit,
    ): Map<String, String> {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            onDiagnostic(error(source, "翻译文件 JSON 解析失败: ${e.message}"))
            return emptyMap()
        }
        return buildMap {
            root.forEach { (key, element) ->
                (element as? JsonPrimitive)?.contentOrNull?.let { put(key, it) }
            }
        }
    }
}

internal fun normalizeProjectPath(path: String): String = path.removePrefix("./")
