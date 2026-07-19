package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition

sealed interface ProjectLoadResult {
    data class Ready(val definition: ProjectDefinition, val diagnostics: List<Diagnostic>) : ProjectLoadResult
    data class Failure(val diagnostics: List<Diagnostic>) : ProjectLoadResult
}

/**
 * 合并 tasks/ 下全部 PI 分片文件为一个 ProjectDefinition，并做跨文件校验：
 * 重名 task/option、悬空 option 引用、option cycle、preset 引用缺失。
 *
 * 文本物化（对齐 MXU contentResolver）：$i18n 按 languages 声明查表（查无回落 key），
 * description 级字段的文件路径形态在加载期读入；URL 形态原样保留给 UI 懒加载。
 *
 * @param locale 期望语言 tag（如 zh-CN）；null 时取系统默认。
 */
class ProjectLoader(
    private val source: ProjectSource,
    private val locale: String? = null,
) {

    fun load(): ProjectLoadResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val projectInterface = loadInterface(diagnostics)
        val translations = loadTranslations(projectInterface, diagnostics)
        val text = buildTextResolver(translations, diagnostics)

        val files = try {
            walkJsonFiles("tasks")
        } catch (e: Exception) {
            diagnostics += error("tasks", "无法枚举 PI 文件: ${e.message}")
            return ProjectLoadResult.Failure(diagnostics)
        }
        if (files.isEmpty()) {
            diagnostics += error("tasks", "项目内没有任何 PI 声明文件")
            return ProjectLoadResult.Failure(diagnostics)
        }

        val tasks = mutableListOf<TaskDefinition>()
        val options = linkedMapOf<String, OptionDefinition>()
        val templates = mutableListOf<ConfigurationTemplate>()
        // 声明顺序合并：根 interface.json 优先，import 分片按文件顺序追加，重名先定义优先
        // 根文件解析早于翻译表加载，label/description 在此处后置物化
        val declaredGroups = mutableListOf<TaskGroupDefinition>()
        projectInterface?.groups?.forEach { group ->
            val resolved = group.copy(
                label = text.label(group.label) ?: group.name,
                description = text.description(group.description),
            )
            mergeGroup(declaredGroups, resolved, "interface.json", diagnostics)
        }

        for (file in files.sorted()) {
            val content = try {
                source.read(file)
            } catch (e: Exception) {
                diagnostics += error(file, "读取失败: ${e.message}")
                continue
            }
            val parsed = PiParser.parseFile(file, content, text)
            diagnostics += parsed.diagnostics
            for (task in parsed.tasks) {
                if (tasks.any { it.name == task.name }) {
                    diagnostics += error(file, "task \"${task.name}\" 重复声明，保留先出现的定义")
                } else {
                    tasks += task
                }
            }
            for ((name, option) in parsed.options) {
                if (options.containsKey(name)) {
                    diagnostics += error(file, "option \"$name\" 重复声明，保留先出现的定义")
                } else {
                    options[name] = option
                }
            }
            for (template in parsed.templates) {
                if (templates.any { it.name == template.name }) {
                    diagnostics += warning(file, "preset \"${template.name}\" 重复声明，保留先出现的定义")
                } else {
                    templates += template
                }
            }
            for (group in parsed.groups) {
                mergeGroup(declaredGroups, group, file, diagnostics)
            }
        }

        validateOptionReferences(tasks, options, diagnostics)
        detectOptionCycles(options, diagnostics)
        validateTemplates(templates, tasks, diagnostics)

        val (normalizedTasks, groups) = resolveGroups(declaredGroups, tasks, diagnostics)

        val definition = ProjectDefinition(
            name = projectInterface?.name ?: source.projectName,
            version = projectInterface?.version,
            controller = ControllerDefinition(),
            resources = projectInterface?.resources
                ?.takeIf { it.isNotEmpty() }
                ?: deriveResources(diagnostics),
            tasks = normalizedTasks,
            groups = groups,
            options = options,
            templates = templates,
        )
        return ProjectLoadResult.Ready(definition, diagnostics)
    }

    /** 按 languages 声明加载当前语言的翻译表：精确 tag -> 语言前缀 -> 首个声明。 */
    private fun loadTranslations(
        projectInterface: PiInterfaceContent?,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, String> {
        val languages = projectInterface?.languages.orEmpty()
        if (languages.isEmpty()) return emptyMap()

        val desired = locale ?: java.util.Locale.getDefault().toLanguageTag()
        val lang = languages.keys.firstOrNull { it.equals(desired, ignoreCase = true) }
            ?: languages.keys.firstOrNull {
                it.substringBefore('-').equals(desired.substringBefore('-'), ignoreCase = true)
            }
            ?: languages.keys.first()

        val path = normalizeProjectPath(languages.getValue(lang))
        val content = try {
            source.read(path)
        } catch (e: Exception) {
            diagnostics += warning(path, "翻译文件读取失败: ${e.message}")
            return emptyMap()
        }
        return PiParser.parseTranslations(path, content) { diagnostics += it }
    }

    /** $i18n 查表（查无回落 key，同 MXU）；description 追加文件形态物化。 */
    private fun buildTextResolver(
        translations: Map<String, String>,
        diagnostics: MutableList<Diagnostic>,
    ): PiTextResolver = object : PiTextResolver {

        private fun i18n(raw: String): String {
            if (!raw.startsWith("$")) return raw
            val key = raw.substring(1)
            return translations[key] ?: key
        }

        override fun label(raw: String?): String? = raw?.let(::i18n)

        override fun description(raw: String?): String? {
            val resolved = raw?.let(::i18n) ?: return null
            if (!isFilePath(resolved)) return resolved
            val path = normalizeProjectPath(resolved)
            return try {
                source.read(path)
            } catch (e: Exception) {
                diagnostics += warning(path, "description 文件读取失败，保留原始文本: ${e.message}")
                resolved
            }
        }
    }

    private fun loadInterface(diagnostics: MutableList<Diagnostic>): PiInterfaceContent? {
        val path = "interface.json"
        val content = try {
            source.read(path)
        } catch (e: Exception) {
            diagnostics += warning(path, "读取失败，将从目录结构回退加载: ${e.message}")
            return null
        }
        return PiParser.parseInterface(path, content).also {
            diagnostics += it.diagnostics
        }
    }

    private fun walkJsonFiles(dir: String): List<String> {
        val result = mutableListOf<String>()
        for (entry in source.list(dir)) {
            val path = "$dir/$entry"
            if (entry.endsWith(".json")) {
                result += path
            } else if (source.list(path).isNotEmpty()) {
                result += walkJsonFiles(path)
            }
        }
        return result
    }

    private fun mergeGroup(
        declared: MutableList<TaskGroupDefinition>,
        group: TaskGroupDefinition,
        source: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (declared.any { it.name == group.name }) {
            diagnostics += warning(source, "group \"${group.name}\" 重复声明，保留先出现的定义")
        } else {
            declared += group
        }
    }

    /**
     * 以顶层 group[] 声明为准归一化任务分组（对齐 MXU v2.4.0）：
     * - 无任何声明即无分组：任务级引用被忽略，全部归入「未分组」；
     * - 有声明时任务只保留命中声明的引用，未命中引用丢弃并记 warning；
     * - 有效引用为空的任务落「未分组」，该组仅在需要时追加为最后一组。
     */
    private fun resolveGroups(
        declared: List<TaskGroupDefinition>,
        tasks: List<TaskDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ): Pair<List<TaskDefinition>, List<TaskGroupDefinition>> {
        if (declared.isEmpty()) {
            val flattened = tasks.map { if (it.groups.isEmpty()) it else it.copy(groups = emptyList()) }
            val groups = if (flattened.isEmpty()) emptyList() else listOf(TaskGroupDefinition(UNGROUPED))
            return flattened to groups
        }
        val declaredNames = declared.mapTo(mutableSetOf()) { it.name }
        var hasUngrouped = false
        val normalized = tasks.map { task ->
            val kept = task.groups.filter { it in declaredNames }
            task.groups.filterNot { it in declaredNames }.forEach { ref ->
                diagnostics += warning("task:${task.name}", "引用了未声明的 group \"$ref\"")
            }
            if (kept.isEmpty()) hasUngrouped = true
            if (kept.size == task.groups.size) task else task.copy(groups = kept)
        }
        val groups = if (hasUngrouped) declared + TaskGroupDefinition(UNGROUPED) else declared
        return normalized to groups
    }

    private fun validateOptionReferences(
        tasks: List<TaskDefinition>,
        options: Map<String, OptionDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        for (task in tasks) {
            for (ref in task.optionNames) {
                if (ref !in options) {
                    diagnostics += error("task:${task.name}", "引用了不存在的 option \"$ref\"")
                }
            }
        }
        for (option in options.values) {
            for (case in option.casesOrEmpty()) {
                for (child in case.childOptionNames) {
                    if (child !in options) {
                        diagnostics += error("option:${option.name}", "case \"${case.name}\" 引用了不存在的 option \"$child\"")
                    }
                }
            }
        }
    }

    private fun detectOptionCycles(
        options: Map<String, OptionDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val visiting = linkedSetOf<String>()
        val done = mutableSetOf<String>()

        fun visit(name: String) {
            if (name in done || name !in options) return
            if (!visiting.add(name)) {
                diagnostics += error("option:$name", "option 引用形成 cycle: ${visiting.joinToString(" -> ")} -> $name")
                return
            }
            options[name]?.casesOrEmpty()?.forEach { case ->
                case.childOptionNames.forEach { visit(it) }
            }
            visiting.remove(name)
            done += name
        }
        options.keys.forEach { visit(it) }
    }

    private fun validateTemplates(
        templates: List<ConfigurationTemplate>,
        tasks: List<TaskDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val taskNames = tasks.mapTo(mutableSetOf()) { it.name }
        for (template in templates) {
            template.tasks.filter { it.taskName !in taskNames }.forEach {
                diagnostics += warning("preset:${template.name}", "引用了不存在的任务 \"${it.taskName}\"")
            }
        }
    }

    /** interface.json 未提供可用声明时，从 resource/ 目录派生兜底资源。 */
    private fun deriveResources(diagnostics: MutableList<Diagnostic>): List<ResourceDefinition> {
        val dirs = try {
            source.list("resource").filter { it != "announcement" && source.list("resource/$it").isNotEmpty() }
        } catch (e: Exception) {
            diagnostics += warning("resource", "无法枚举 resource 目录: ${e.message}")
            emptyList()
        }
        if (dirs.isEmpty()) {
            diagnostics += warning("resource", "没有可用的 resource")
            return emptyList()
        }
        val hasBase = "base" in dirs
        val variants = dirs.filter { it != "base" }.sorted()
        val result = mutableListOf<ResourceDefinition>()
        if (hasBase) {
            result += ResourceDefinition("base", listOf("resource/base"))
        }
        for (dir in variants) {
            val paths = if (hasBase) listOf("resource/base", "resource/$dir") else listOf("resource/$dir")
            result += ResourceDefinition(dir, paths)
        }
        return result
    }

    private fun error(source: String, message: String) = Diagnostic(DiagnosticSeverity.Error, source, message)
    private fun warning(source: String, message: String) = Diagnostic(DiagnosticSeverity.Warning, source, message)

    companion object {
        const val UNGROUPED = "未分组"
    }
}

internal fun OptionDefinition.casesOrEmpty() = when (this) {
    is OptionDefinition.Select -> cases
    is OptionDefinition.Switch -> cases
    is OptionDefinition.Checkbox -> cases
    is OptionDefinition.Input -> emptyList()
}
