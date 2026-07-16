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
import com.aliothmoon.maafw.domain.TemplateTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 单个 PI 分片文件（task[] / option{} / preset[]）的解析结果。 */
data class PiFileContent(
    val tasks: List<TaskDefinition> = emptyList(),
    val options: Map<String, OptionDefinition> = emptyMap(),
    val templates: List<ConfigurationTemplate> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** PI 根 interface.json 中当前领域模型需要的项目元数据。 */
data class PiInterfaceContent(
    val name: String?,
    val version: String?,
    val resources: List<ResourceDefinition>,
    val diagnostics: List<Diagnostic>,
)

/**
 * PI V2 分片文件解析器。宽容解析：字段级错误降级为诊断并跳过该条目，
 * 不让单个坏条目阻断整个项目加载。
 */
object PiParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseInterface(source: String, content: String): PiInterfaceContent {
        val diagnostics = mutableListOf<Diagnostic>()
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            diagnostics += error(source, "JSON 解析失败: ${e.message}")
            return PiInterfaceContent(null, null, emptyList(), diagnostics)
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

        return PiInterfaceContent(
            name = root.string("name"),
            version = root.string("version"),
            resources = resources,
            diagnostics = diagnostics,
        )
    }

    fun parseFile(source: String, content: String): PiFileContent {
        val diagnostics = mutableListOf<Diagnostic>()
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            diagnostics += error(source, "JSON 解析失败: ${e.message}")
            return PiFileContent(diagnostics = diagnostics)
        }

        val tasks = (root["task"] as? JsonArray).orEmpty().mapNotNull { element ->
            parseTask(source, element, diagnostics)
        }
        val options = buildMap {
            (root["option"] as? JsonObject)?.forEach { (name, element) ->
                parseOption(source, name, element, diagnostics)?.let { put(name, it) }
            }
        }
        val templates = (root["preset"] as? JsonArray).orEmpty().mapNotNull { element ->
            parsePreset(source, element, diagnostics)
        }
        return PiFileContent(tasks, options, templates, diagnostics)
    }

    private fun parseTask(
        source: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
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
            description = obj.string("description") ?: obj.string("desc"),
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
    ): OptionDefinition? {
        val obj = element as? JsonObject
            ?: return null.also { diagnostics += error(source, "option \"$name\" 不是对象") }
        val label = obj.string("label") ?: name
        val description = obj.string("description")
        return when (val type = obj.string("type")) {
            "select", "switch" -> {
                val cases = parseCases(source, name, obj, diagnostics)
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
                val cases = parseCases(source, name, obj, diagnostics)
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
                    parseInputField(source, name, field, diagnostics)
                }
                if (fields.isEmpty()) {
                    diagnostics += warning(source, "input option \"$name\" 没有可用的 inputs")
                }
                OptionDefinition.Input(name, label, description, fields, obj.objectOrEmpty("pipeline_override"))
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
    ): List<OptionCaseDefinition> =
        (obj["cases"] as? JsonArray).orEmpty().mapNotNull { element ->
            val case = element as? JsonObject
                ?: return@mapNotNull null.also { diagnostics += error(source, "option \"$optionName\" 存在非对象 case") }
            val caseName = case.string("name")
                ?: return@mapNotNull null.also { diagnostics += error(source, "option \"$optionName\" 存在缺少 name 的 case") }
            OptionCaseDefinition(
                name = caseName,
                label = case.string("label") ?: caseName,
                description = case.string("description"),
                pipelineOverride = case.objectOrEmpty("pipeline_override"),
                childOptionNames = case.stringList("option"),
            )
        }

    private fun parseInputField(
        source: String,
        optionName: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
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
            patternMessage = obj.string("pattern_msg"),
            description = obj.string("description"),
        )
    }

    private fun parsePreset(
        source: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
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
        return ConfigurationTemplate(name, obj.string("description"), tasks)
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

    private fun normalizeProjectPath(path: String): String = path.removePrefix("./")
}
