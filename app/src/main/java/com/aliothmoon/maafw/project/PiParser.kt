package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.AgentDefinition
import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.error
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessages
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionApplicability
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TelemetryDefinition
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** 单个 PI 分片文件（task[] / option{} / global_option[] / preset[] / group[]）的解析结果 */
data class PiFileContent(
    val tasks: List<TaskDefinition> = emptyList(),
    val options: Map<String, OptionDefinition> = emptyMap(),
    /** 只是 option 键名，定义仍在 [options] 里；引用完整性由 ProjectLoader 校验 */
    val globalOptionNames: List<String> = emptyList(),
    val templates: List<ConfigurationTemplate> = emptyList(),
    val groups: List<TaskGroupDefinition> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** translations 加载前的 resource 声明；label 仍保留 PI 原始值 */
data class PiResourceContent(
    val name: String,
    val paths: List<String>,
    val label: String?,
    val icon: String? = null,
    /** 原样条目，PI_RESOURCE 要整条 */
    val raw: JsonObject = JsonObject(emptyMap()),
    val optionNames: List<String> = emptyList(),
)

/**
 * controller 声明的原样投影；挑哪一个由 loader 按平台决定
 * [name] 是 task 的 controller[] 实际引用的标识，不能用 type 代替
 */
data class PiControllerContent(
    val name: String,
    val type: String,
    /** 三者互斥；都缺省时由外壳按默认分辨率兜底 */
    val displayShortSide: Int? = null,
    val displayLongSide: Int? = null,
    val displayRaw: Boolean = false,
    /** 原样条目，PI_CONTROLLER 要整条 */
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** PI 根 interface.json 中当前领域模型需要的项目元数据 */
data class PiInterfaceContent(
    val name: String?,
    val version: String?,
    /** 顶层 interface_version；PI V2 要求恒为 2，缺失或非法由 loader 硬失败 */
    val interfaceVersion: Long?,
    val resources: List<PiResourceContent>,
    val controllers: List<PiControllerContent>,
    /** 顶层 agent 声明，按 PI 里的顺序；单对象与数组都归一成列表 */
    val agents: List<AgentDefinition> = emptyList(),
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
            diagnostics += error(source, DiagnosticMessages.jsonParseFailed(e.message.orEmpty()))
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
                    diagnostics += error(source, DiagnosticMessages.entryNotObject("resource"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("resource", "name"),
                    )
                }
            val paths = obj.stringList("path").map(::normalizeProjectPath)
            if (paths.isEmpty()) {
                diagnostics += error(source, DiagnosticMessages.resourcePathMissing(name))
                null
            } else {
                PiResourceContent(
                    name,
                    paths,
                    obj.string("label"),
                    obj.iconPath(),
                    obj,
                    obj.stringList("option"),
                )
            }
        }

        // name/type 缺一不可：缺 name 无法被 task 引用，缺 type 无法判定平台
        val controllers = (root["controller"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessages.entryNotObject("controller"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("controller", "name"),
                    )
                }
            val type = obj.string("type")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("controller", "type", owner = name),
                    )
                }
            PiControllerContent(
                name = name,
                type = type,
                displayShortSide = obj.int("display_short_side"),
                displayLongSide = obj.int("display_long_side"),
                displayRaw = obj.boolean("display_raw") ?: false,
                raw = obj,
            )
        }

        val languages = buildMap {
            (root["languages"] as? JsonObject)?.forEach { (lang, element) ->
                val path = (element as? JsonPrimitive)?.contentOrNull
                if (path != null) {
                    put(lang, path)
                } else {
                    diagnostics += warning(source, DiagnosticMessages.languagePathInvalid(lang))
                }
            }
        }

        return PiInterfaceContent(
            name = root.string("name"),
            version = root.string("version"),
            interfaceVersion = (root["interface_version"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            resources = resources,
            controllers = controllers,
            agents = parseAgents(source, root, diagnostics),
            languages = languages,
            imports = root.stringList("import").map(::normalizeProjectPath),
            diagnostics = diagnostics,
            root = root,
        )
    }

    /**
     * 顶层 `agent`：PI 允许单对象与数组两种写法（上游 `Configurator.cpp` 用 std::visit 收两种）
     * `child_exec` 为空的条目直接跳过，与上游一致——它是唯一必填项
     */
    private fun parseAgents(
        source: String,
        root: JsonObject,
        diagnostics: MutableList<Diagnostic>,
    ): List<AgentDefinition> {
        val entries = when (val value = root["agent"]) {
            is JsonArray -> value
            is JsonObject -> listOf(value)
            else -> return emptyList()
        }
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessages.entryNotObject("agent"))
                }
            val childExec = obj.string("child_exec")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("agent", "child_exec"),
                    )
                }
            AgentDefinition(childExec = childExec, childArgs = obj.stringList("child_args"))
        }
    }

    /** 与 [parseInterface] 分开是因为物化要等翻译表，那一步在 loader 里读完 languages 才有 */
    fun parseMetadata(root: JsonObject, text: PiTextResolver): ProjectMetadata {
        val welcomeRaw = root.string("welcome")
        return ProjectMetadata(
            welcome = text.description(welcomeRaw),
            welcomeFingerprint = welcomeRaw?.let { welcomeFingerprint(it, root.string("version")) },
            description = text.description(root.string("description")),
            contact = text.description(root.string("contact")),
            license = text.description(root.string("license")),
            github = text.label(root.string("github"))?.takeIf(::isRemoteUrl),
            githubRepository = root.string("github")
                ?.let(::githubRepository)
                ?.takeIf(String::isNotBlank),
            mirrorChyanRid = root.string("mirrorchyan_rid")?.trim()?.takeIf(String::isNotBlank),
        )
    }

    /** 只认 github.com/<owner>/<repo>；仓库页 URL 常带 release 等后续路径，都截掉 */
    private fun githubRepository(rawUrl: String): String? {
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        if (url.host != "github.com") return null
        val owner = url.pathSegments.getOrNull(0)?.takeIf(String::isNotBlank) ?: return null
        val repository = url.pathSegments.getOrNull(1)?.removeSuffix(".git")?.takeIf(String::isNotBlank) ?: return null
        return "$owner/$repository"
    }

    fun parseTelemetry(root: JsonObject): TelemetryDefinition? {
        val sentry = (root["telemetry"] as? JsonObject)?.get("sentry") as? JsonObject ?: return null
        val dsn = sentry.string("dsn")?.takeIf(String::isNotBlank) ?: return null
        return TelemetryDefinition(
            dsn = dsn,
            tracing = sentry.boolean("tracing") ?: true,
            tracesSampleRate = sentry.string("traces_sample_rate")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
            environment = sentry.string("environment")?.takeIf(String::isNotBlank),
        )
    }

    private fun welcomeFingerprint(raw: String, version: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$raw@${version.orEmpty()}".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    fun parseFile(source: String, content: String, text: PiTextResolver): PiFileContent {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            return PiFileContent(
                diagnostics = listOf(error(source, DiagnosticMessages.jsonParseFailed(e.message.orEmpty()))),
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
        return PiFileContent(
            tasks = tasks,
            options = options,
            globalOptionNames = root.stringList("global_option"),
            templates = templates,
            groups = groups,
            diagnostics = diagnostics,
        )
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
                    diagnostics += error(source, DiagnosticMessages.entryNotObject("group"))
                }
            val name = obj.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("group", "name"),
                    )
                }
            TaskGroupDefinition(
                name = name,
                label = text.label(obj.string("label")) ?: name,
                description = text.description(obj.string("description")),
                icon = obj.iconPath(),
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
                diagnostics += error(source, DiagnosticMessages.entryNotObject("task"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessages.requiredFieldMissing("task", "name"))
            }
        val entry = obj.string("entry")
            ?: return null.also {
                diagnostics += error(
                    source,
                    DiagnosticMessages.requiredFieldMissing("task", "entry", owner = name),
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
            icon = obj.iconPath(),
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
                diagnostics += error(source, DiagnosticMessages.entryNotObject("option \"$name\""))
            }
        val label = text.label(obj.string("label")) ?: name
        val description = text.description(obj.string("description"))
        val icon = obj.iconPath()
        // controller 名在 Android 上只可能是 PI 声明的那一个 Adb 项
        val applicability = OptionApplicability(
            controllers = obj.stringList("controller"),
            resources = obj.stringList("resource"),
        )
        return when (val type = obj.string("type")) {
            "select", "switch" -> {
                val cases = parseCases(source, name, obj, diagnostics, text)
                val defaultCase = obj.string("default_case")?.also {
                    if (cases.none { c -> c.name == it }) {
                        diagnostics += warning(source, DiagnosticMessages.defaultCaseMissing(name, it))
                    }
                }?.takeIf { d -> cases.any { it.name == d } }
                if (type == "select") {
                    OptionDefinition.Select(name, label, description, cases, defaultCase, icon, applicability)
                } else {
                    OptionDefinition.Switch(name, label, description, cases, defaultCase, icon, applicability)
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
                            diagnostics += warning(source, DiagnosticMessages.defaultCaseMissing(name, d))
                        }
                    }
                }
                OptionDefinition.Checkbox(name, label, description, cases, defaults, icon, applicability)
            }

            "input" -> {
                val fields = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { field ->
                    parseInputField(source, name, field, diagnostics, text)
                }
                if (fields.isEmpty()) {
                    diagnostics += warning(source, DiagnosticMessages.inputHasNoFields(name))
                }
                OptionDefinition.Input(
                    name,
                    label,
                    description,
                    fields,
                    obj.objectOrEmpty("pipeline_override"),
                    icon,
                    applicability,
                )
            }

            // 协议允许的类型，但热键是桌面端语义，Android 端跳过不投影
            "hotkey" -> {
                diagnostics += warning(
                    source,
                    DiagnosticMessages.unsupportedOptionType(name, "hotkey"),
                )
                null
            }

            else -> {
                diagnostics += error(source, DiagnosticMessages.invalidOptionType(name, type))
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
                    diagnostics += error(source, DiagnosticMessages.optionCaseNotObject(optionName))
                }
            val caseName = case.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessages.optionCaseNameMissing(optionName))
                }
            OptionCaseDefinition(
                name = caseName,
                label = text.label(case.string("label")) ?: caseName,
                description = text.description(case.string("description")),
                pipelineOverride = case.objectOrEmpty("pipeline_override"),
                childOptionNames = case.stringList("option"),
                icon = case.iconPath(),
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
                diagnostics += error(source, DiagnosticMessages.entryNotObject("input field"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(
                    source,
                    DiagnosticMessages.requiredFieldMissing("input field", "name", owner = optionName),
                )
            }
        val pipelineType = when (val t = obj.string("pipeline_type")?.lowercase()) {
            null, "string" -> PipelineType.StringType
            "int" -> PipelineType.IntType
            "bool" -> PipelineType.BoolType
            else -> {
                diagnostics += warning(
                    source,
                    DiagnosticMessages.invalidPipelineType(optionName, name, t),
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
                    DiagnosticMessages.regexCompileFailed(
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
                diagnostics += error(source, DiagnosticMessages.entryNotObject("preset"))
            }
        val name = obj.string("name")
            ?: return null.also {
                diagnostics += error(source, DiagnosticMessages.requiredFieldMissing("preset", "name"))
            }
        val tasks = (obj["task"] as? JsonArray).orEmpty().mapNotNull { taskElement ->
            val task = taskElement as? JsonObject
                ?: return@mapNotNull null.also {
                    diagnostics += error(source, DiagnosticMessages.entryNotObject("preset task"))
                }
            val taskName = task.string("name")
                ?: return@mapNotNull null.also {
                    diagnostics += error(
                        source,
                        DiagnosticMessages.requiredFieldMissing("preset task", "name", owner = name),
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
            icon = obj.iconPath(),
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

    /** icon 是路径不是文案，不过 [PiTextResolver]：$ 开头的路径会被当成 i18n key 查表 */
    private fun JsonObject.iconPath(): String? = string("icon")?.let(::normalizeProjectPath)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

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
                error(source, DiagnosticMessages.translationJsonParseFailed(e.message.orEmpty())),
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
