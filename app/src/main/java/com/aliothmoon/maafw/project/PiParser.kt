package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.error
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessage
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
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

/** 单个 PI 分片文件（task[] / option{} / preset[] / group[]）的解析结果 */
data class PiFileContent(
    val tasks: List<TaskDefinition> = emptyList(),
    val options: Map<String, OptionDefinition> = emptyMap(),
    val templates: List<ConfigurationTemplate> = emptyList(),
    val groups: List<TaskGroupDefinition> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** translations 加载前的 resource 声明；label 仍保留 PI 原始值 */
data class PiResourceContent(
    val name: String,
    val paths: List<String>,
    val label: String?,
)

/**
 * controller 声明的原样投影；挑哪一个由 loader 按平台决定
 * [name] 是 task 的 controller[] 实际引用的标识，不能用 type 代替
 */
data class PiControllerContent(
    val name: String,
    val type: String,
)

/** PI 根 interface.json 中当前领域模型需要的项目元数据 */
data class PiInterfaceContent(
    val name: String?,
    val version: String?,
    /** 顶层 interface_version；PI V2 要求恒为 2，缺失或非法由 loader 硬失败 */
    val interfaceVersion: Long?,
    val resources: List<PiResourceContent>,
    val controllers: List<PiControllerContent>,
    /** v2 languages 声明：语言 tag -> 翻译文件相对路径 */
    val languages: Map<String, String>,
    /** v2.2.0 import 分片声明，按数组顺序加载；路径相对 interface.json 目录 */
    val imports: List<String>,
    val diagnostics: List<Diagnostic>,
    /** 已解析的根 JSON（解析失败为 null），供 loader 二次提取内容时免去重复解析 */
    val root: JsonObject? = null,
)

/**
 * 解析期文本物化钩子（对齐桌面端 MXU 对 label/description 的处理习惯）：
 * label 级字段只做 $i18n 查表；description 级字段追加文件形态读取
 * URL 形态一律原样保留，由 UI 层懒加载
 */
interface PiTextResolver {
    fun label(raw: String?): String?
    fun description(raw: String?): String?
}

/**
 * PI V2 分片文件解析器宽容解析：字段级错误降级为诊断并跳过该条目，
 * 不让单个坏条目阻断整个项目加载
 */
object PiParser {

    // PI 生态普遍使用 JSONC（注释 + 尾逗号），与 MXU 等客户端的宽容解析一致
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    /**
     * 解析根 interface.json 的项目元数据（版本 / 资源 / 语言 / import 声明）
     * 根文件自身声明的 task/option/preset/group 在翻译表就绪后由 [parseFile] 基于
     * 返回的 [PiInterfaceContent.root] 再次提取（只解析一次 JSON），
     * 避免元数据阶段无法物化 $i18n 的鸡生蛋问题
     */
    fun parseInterface(source: String, content: String): PiInterfaceContent {
        val diagnostics = mutableListOf<Diagnostic>()
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            diagnostics += error(source, DiagnosticMessage.JsonParseFailed(e.message.orEmpty()))
            return PiInterfaceContent(
                name = null,
                version = null,
                interfaceVersion = null,
                resources = emptyList(),
                controllers = emptyList(),
                languages = emptyMap(),
                imports = emptyList(),
                diagnostics = diagnostics,
            )
        }

        val resources = (root["resource"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.EntryNotObject("resource"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessage.RequiredFieldMissing("resource", "name"),
                    )
                }
            val paths = obj.stringList("path").map(::normalizeProjectPath)
            if (paths.isEmpty()) {
                diagnostics += error(source, DiagnosticMessage.ResourcePathMissing(name))
                null
            } else {
                PiResourceContent(name, paths, obj.string("label"))
            }
        }

        // name/type 缺一不可：缺 name 无法被 task 引用，缺 type 无法判定平台
        val controllers = (root["controller"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.EntryNotObject("controller"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessage.RequiredFieldMissing("controller", "name"),
                    )
                }
            val type = obj.string("type")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessage.RequiredFieldMissing("controller", "type", owner = name),
                    )
                }
            PiControllerContent(name, type)
        }

        val languages = buildMap {
            (root["languages"] as? JsonObject)?.forEach { (lang, element) ->
                val path = (element as? JsonPrimitive)?.contentOrNull
                if (path != null) {
                    put(lang, path)
                } else {
                    diagnostics += warning(source, DiagnosticMessage.LanguagePathInvalid(lang))
                }
            }
        }

        return PiInterfaceContent(
            name = root.string("name"),
            version = root.string("version"),
            interfaceVersion = (root["interface_version"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            resources = resources,
            controllers = controllers,
            languages = languages,
            imports = root.stringList("import").map(::normalizeProjectPath),
            diagnostics = diagnostics,
            root = root,
        )
    }

    fun parseFile(source: String, content: String, text: PiTextResolver): PiFileContent {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            return PiFileContent(
                diagnostics = listOf(error(source, DiagnosticMessage.JsonParseFailed(e.message.orEmpty()))),
            )
        }
        return parseFile(source, root, text)
    }

    /** 已解析根对象的内容提取：根 interface.json 复用 [parseInterface] 的解析结果走这里 */
    fun parseFile(source: String, root: JsonObject, text: PiTextResolver): PiFileContent {
        val diagnostics = mutableListOf<Diagnostic>()
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

    /** v2.4.0 顶层 group[] 声明：根 interface.json 与 import 分片均可出现 */
    private fun parseGroups(
        source: String,
        root: JsonObject,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): List<TaskGroupDefinition> =
        (root["group"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.EntryNotObject("group"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessage.RequiredFieldMissing("group", "name"),
                    )
                }
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
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.EntryNotObject("task"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.RequiredFieldMissing("task", "name"))
            }
        val entry = obj.string("entry")
            ?: return null.also {
                diagnostics += error(
                    source,
                    DiagnosticMessage.RequiredFieldMissing("task", "entry", owner = name),
                )
            }
        return TaskDefinition(
            name = name,
            entry = entry,
            label = text.label(obj.string("label")) ?: name,
            description = text.description(obj.string("description") ?: obj.string("desc")),
            groups = obj.stringList("group"),
            optionNames = obj.stringList("option"),
            pipelineOverride = obj.objectOrEmpty("pipeline_override"),
            controllers = obj.stringList("controller"),
            resources = obj.stringList("resource"),
            // 规范键 default_check 优先，静默兼容早期数据的 check
            defaultCheck = obj.boolean("default_check") ?: obj.boolean("check") ?: false,
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
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.EntryNotObject("option \"$name\""))
            }
        val label = text.label(obj.string("label")) ?: name
        val description = text.description(obj.string("description"))
        return when (val type = obj.string("type")) {
            "select", "switch" -> {
                val cases = parseCases(source, name, obj, diagnostics, text)
                val defaultCase = obj.string("default_case")?.also {
                    if (cases.none { c -> c.name == it }) {
                        diagnostics += warning(source, DiagnosticMessage.DefaultCaseMissing(name, it))
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
                        if (!found) {
                            diagnostics += warning(source, DiagnosticMessage.DefaultCaseMissing(name, d))
                        }
                    }
                }
                OptionDefinition.Checkbox(name, label, description, cases, defaults)
            }

            "input" -> {
                val fields = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { field ->
                    parseInputField(source, name, field, diagnostics, text)
                }
                if (fields.isEmpty()) {
                    diagnostics += warning(source, DiagnosticMessage.InputHasNoFields(name))
                }
                OptionDefinition.Input(name, label, description, fields, obj.objectOrEmpty("pipeline_override"))
            }

            // 协议允许的类型，但热键是桌面端语义，Android 端跳过不投影
            "hotkey" -> {
                diagnostics += warning(
                    source,
                    DiagnosticMessage.UnsupportedOptionType(name, "hotkey"),
                )
                null
            }

            else -> {
                diagnostics += error(source, DiagnosticMessage.InvalidOptionType(name, type))
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
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.OptionCaseNotObject(optionName))
                }
            val caseName = case.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.OptionCaseNameMissing(optionName))
                }
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
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.EntryNotObject("input field"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(
                    source,
                    DiagnosticMessage.RequiredFieldMissing("input field", "name", owner = optionName),
                )
            }
        val pipelineType = when (val t = obj.string("pipeline_type")?.lowercase()) {
            null, "string" -> PipelineType.StringType
            "int" -> PipelineType.IntType
            "bool" -> PipelineType.BoolType
            else -> {
                diagnostics += warning(
                    source,
                    DiagnosticMessage.InvalidPipelineType(optionName, name, t),
                )
                PipelineType.StringType
            }
        }
        val verifyPattern = obj.string("verify")
        val verify = verifyPattern?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                diagnostics += error(
                    source,
                    DiagnosticMessage.RegexCompileFailed(
                        option = optionName,
                        input = name,
                        detail = e.message.orEmpty(),
                    ),
                )
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
            label = text.label(obj.string("label")) ?: name,
        )
    }

    private fun parsePreset(
        source: String,
        element: JsonElement,
        diagnostics: MutableList<Diagnostic>,
        text: PiTextResolver,
    ): ConfigurationTemplate? {
        val obj = element as? JsonObject
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.EntryNotObject("preset"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessage.RequiredFieldMissing("preset", "name"))
            }
        val tasks = (obj["task"] as? JsonArray).orEmpty().mapNotNull { taskElement ->
            val task = taskElement as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessage.EntryNotObject("preset task"))
                }
            val taskName = task.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessage.RequiredFieldMissing("preset task", "name", owner = name),
                    )
                }
            TemplateTask(
                taskName = taskName,
                enabled = task.boolean("enabled") ?: true,
                optionValues = parsePresetOptionValues(task["option"]),
            )
        }
        return ConfigurationTemplate(
            name = name,
            label = text.label(obj.string("label")) ?: name,
            description = text.description(obj.string("description")),
            tasks = tasks,
        )
    }

    /** preset 的 option 值形态：string -> SingleCase，array -> MultipleCases，object -> Inputs */
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

    /** 翻译文件：扁平的 key -> 译文 JSON 对象 */
    fun parseTranslations(
        source: String,
        content: String,
        onDiagnostic: (Diagnostic) -> Unit,
    ): Map<String, String> {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            onDiagnostic(
                error(source, DiagnosticMessage.TranslationJsonParseFailed(e.message.orEmpty())),
            )
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
