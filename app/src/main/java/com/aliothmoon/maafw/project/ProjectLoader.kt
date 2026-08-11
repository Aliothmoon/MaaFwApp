package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.error
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessages
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.casesOrEmpty

sealed interface ProjectLoadResult {
    data class Ready(val definition: ProjectDefinition, val diagnostics: List<Diagnostic>) : ProjectLoadResult
    data class Failure(val diagnostics: List<Diagnostic>) : ProjectLoadResult
}

/**
 * 按 PI V2 语义加载项目：根 interface.json 声明 + import[] 分片按序合并
 * （行为与桌面端 MXU 一致处见 docs/pi-compatibility.md），
 * 并做跨文件校验：重名 task/option、悬空 option 引用、option cycle、preset 引用缺失
 *
 * 文本物化：$i18n 按 languages 声明查表（查无回落 key），
 * description 级字段的文件路径形态在加载期读入；URL 形态原样保留给 UI 懒加载
 *
 * @param localeProvider 每次 load 时查询期望语言 tag（如 zh-CN）；null 表示跟随系统默认
 *   用 provider 而非固定值：loader 是单例，语言切换后 reload 需要拿到新 locale
 */
class ProjectLoader(
    private val source: ProjectSource,
    private val localeProvider: () -> String? = { null },
) {

    private class MergeState {
        val tasks = mutableListOf<TaskDefinition>()
        val options = linkedMapOf<String, OptionDefinition>()
        val templates = mutableListOf<ConfigurationTemplate>()
        val declaredGroups = mutableListOf<TaskGroupDefinition>()
    }

    fun load(): ProjectLoadResult {
        val diagnostics = mutableListOf<Diagnostic>()

        val interfacePath = "interface.json"
        val interfaceContent = try {
            source.read(interfacePath)
        } catch (e: Exception) {
            diagnostics += error(
                interfacePath,
                DiagnosticMessages.interfaceReadFailed(e.message.orEmpty()),
            )
            return ProjectLoadResult.Failure(diagnostics)
        }
        val projectInterface = PiParser.parseInterface(interfacePath, interfaceContent)
        diagnostics += projectInterface.diagnostics

        // PI V2：interface_version 必须为 2，否则拒绝加载
        if (projectInterface.interfaceVersion != 2L) {
            diagnostics += error(
                interfacePath,
                when (val v = projectInterface.interfaceVersion) {
                    null -> DiagnosticMessages.missingInterfaceVersion()
                    else -> DiagnosticMessages.unsupportedInterfaceVersion(v)
                },
            )
            return ProjectLoadResult.Failure(diagnostics)
        }

        val translations = loadTranslations(projectInterface, diagnostics)
        val text = buildTextResolver(translations, diagnostics)

        // 根文件自身作为首个分片（task/option/preset/group），import 分片按声明顺序追加；
        // 重名一律先定义优先；缺失分片降级为 warning 跳过
        // 根 JSON 复用 parseInterface 的解析结果（version 校验通过则 root 必非 null）
        val state = MergeState()
        projectInterface.root?.let {
            mergeContent(state, PiParser.parseFile(interfacePath, it, text), interfacePath, diagnostics)
        }
        for (path in projectInterface.imports) {
            val content = try {
                source.read(path)
            } catch (e: Exception) {
                diagnostics += warning(path, DiagnosticMessages.importReadFailed(e.message.orEmpty()))
                continue
            }
            mergeContent(state, PiParser.parseFile(path, content, text), path, diagnostics)
        }
        if (state.tasks.isEmpty()) {
            diagnostics += warning(interfacePath, DiagnosticMessages.projectHasNoTasks())
        }

        validateOptionReferences(state.tasks, state.options, diagnostics)
        detectOptionCycles(state.options, diagnostics)
        validateTemplates(state.templates, state.tasks, diagnostics)

        val (normalizedTasks, groups) = resolveGroups(state.declaredGroups, state.tasks, diagnostics)

        // 模板任务展示名在此物化：preset 只声明 taskName，label 在任务定义上
        val taskLabels = normalizedTasks.associate { it.name to it.label }
        val templates = state.templates.map { template ->
            template.copy(
                tasks = template.tasks.map { it.copy(label = taskLabels[it.taskName] ?: it.taskName) },
            )
        }

        val definition = ProjectDefinition(
            name = projectInterface.name ?: source.projectName,
            version = projectInterface.version,
            controller = resolveController(interfacePath, projectInterface, diagnostics),
            resources = projectInterface.resources
                .map { ResourceDefinition(it.name, it.paths, text.label(it.label) ?: it.name) }
                .takeIf { it.isNotEmpty() }
                ?: deriveResources(diagnostics),
            tasks = normalizedTasks,
            groups = groups,
            options = state.options,
            templates = templates,
            agents = projectInterface.agents,
            translations = translations,
        )
        return ProjectLoadResult.Ready(definition, diagnostics)
    }

    /**
     * Android 外壳只驱动 Adb controller，从 PI 声明里取该项的真实 name
     * task 的 controller[] 引用的是 controller 名，写死名字会让换一个 PI 后全部任务被判不适用
     * 未声明 Adb 说明该 PI 不面向 Android：记 warning 并回落默认，不阻断加载
     */
    private fun resolveController(
        source: String,
        projectInterface: PiInterfaceContent,
        diagnostics: MutableList<Diagnostic>,
    ): ControllerDefinition {
        val adb = projectInterface.controllers
            .firstOrNull { it.type.equals(ADB_CONTROLLER_TYPE, ignoreCase = true) }
        if (adb == null) {
            diagnostics += warning(source, DiagnosticMessages.noAdbController())
            return ControllerDefinition()
        }
        return ControllerDefinition(
            name = adb.name,
            type = adb.type,
            displayShortSide = adb.displayShortSide,
            displayLongSide = adb.displayLongSide,
            displayRaw = adb.displayRaw,
        )
    }

    /** 分片内容合并进累计状态：task/option 重名 → error，preset/group 重名 → warning，一律先定义优先 */
    private fun mergeContent(
        state: MergeState,
        parsed: PiFileContent,
        file: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        diagnostics += parsed.diagnostics
        for (task in parsed.tasks) {
            if (state.tasks.any { it.name == task.name }) {
                diagnostics += error(file, DiagnosticMessages.duplicateDeclaration("task", task.name))
            } else {
                state.tasks += task
            }
        }
        for ((name, option) in parsed.options) {
            if (state.options.containsKey(name)) {
                diagnostics += error(file, DiagnosticMessages.duplicateDeclaration("option", name))
            } else {
                state.options[name] = option
            }
        }
        for (template in parsed.templates) {
            if (state.templates.any { it.name == template.name }) {
                diagnostics += warning(
                    file,
                    DiagnosticMessages.duplicateDeclaration("preset", template.name),
                )
            } else {
                state.templates += template
            }
        }
        for (group in parsed.groups) {
            if (state.declaredGroups.any { it.name == group.name }) {
                diagnostics += warning(
                    file,
                    DiagnosticMessages.duplicateDeclaration("group", group.name),
                )
            } else {
                state.declaredGroups += group
            }
        }
    }

    /**
     * 按 languages 声明加载当前语言的翻译表：精确 tag -> 语言前缀 -> 首个声明
     * tag 比较前统一小写并把 '_' 归一为 '-'，兼容 zh_cn 这类下划线风格键
     */
    private fun loadTranslations(
        projectInterface: PiInterfaceContent,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, String> {
        val languages = projectInterface.languages
        if (languages.isEmpty()) return emptyMap()

        fun norm(tag: String) = tag.lowercase().replace('_', '-')
        val desired = norm(localeProvider() ?: java.util.Locale.getDefault().toLanguageTag())
        val lang = languages.keys.firstOrNull { norm(it) == desired }
            ?: languages.keys.firstOrNull {
                norm(it).substringBefore('-') == desired.substringBefore('-')
            }
            ?: languages.keys.first()

        val path = normalizeProjectPath(languages.getValue(lang))
        val content = try {
            source.read(path)
        } catch (e: Exception) {
            diagnostics += warning(path, DiagnosticMessages.translationReadFailed(e.message.orEmpty()))
            return emptyMap()
        }
        return PiParser.parseTranslations(path, content) { diagnostics += it }
    }

    /** $i18n 查表（查无回落 key）；description 追加文件形态物化 */
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
                diagnostics += warning(path, DiagnosticMessages.descriptionReadFailed(e.message.orEmpty()))
                resolved
            }
        }
    }

    /**
     * 以顶层 group[] 声明为准归一化任务分组（PI v2.4.0）：
     * - 无任何声明即无分组：任务级引用被忽略，全部归入「未分组」；
     * - 有声明时任务只保留命中声明的引用，未命中引用丢弃并记 warning；
     * - 有效引用为空的任务落「未分组」，该组仅在需要时追加为最后一组
     */
    private fun resolveGroups(
        declared: List<TaskGroupDefinition>,
        tasks: List<TaskDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ): Pair<List<TaskDefinition>, List<TaskGroupDefinition>> {
        if (declared.isEmpty()) {
            val flattened = tasks.map { if (it.groups.isEmpty()) it else it.copy(groups = emptyList()) }
            val groups = if (flattened.isEmpty()) emptyList() else listOf(ungroupedGroup())
            return flattened to groups
        }
        val declaredNames = declared.mapTo(mutableSetOf()) { it.name }
        var hasUngrouped = false
        val normalized = tasks.map { task ->
            val kept = task.groups.filter { it in declaredNames }
            task.groups.filterNot { it in declaredNames }.forEach { ref ->
                diagnostics += warning(
                    "task:${task.name}",
                    DiagnosticMessages.missingReference("group", ref),
                )
            }
            if (kept.isEmpty()) hasUngrouped = true
            if (kept.size == task.groups.size) task else task.copy(groups = kept)
        }
        val groups = if (hasUngrouped) declared + ungroupedGroup() else declared
        return normalized to groups
    }

    /** 合成「未分组」兜底组：消费方按 isUngrouped 标记判定，不依赖显示名 */
    private fun ungroupedGroup() = TaskGroupDefinition(UNGROUPED, isUngrouped = true)

    private fun validateOptionReferences(
        tasks: List<TaskDefinition>,
        options: Map<String, OptionDefinition>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        for (task in tasks) {
            for (ref in task.optionNames) {
                if (ref !in options) {
                    diagnostics += error(
                        "task:${task.name}",
                        DiagnosticMessages.missingReference("option", ref),
                    )
                }
            }
        }
        for (option in options.values) {
            for (case in option.casesOrEmpty()) {
                for (child in case.childOptionNames) {
                    if (child !in options) {
                        diagnostics += error(
                            "option:${option.name}/case:${case.name}",
                            DiagnosticMessages.missingReference("option", child),
                        )
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
                diagnostics += error(
                    "option:$name",
                    DiagnosticMessages.optionCycle("${visiting.joinToString(" -> ")} -> $name"),
                )
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
                diagnostics += warning(
                    "preset:${template.name}",
                    DiagnosticMessages.missingReference("task", it.taskName),
                )
            }
        }
    }

    /** interface.json 未提供可用声明时，从 resource/ 目录派生兜底资源 */
    private fun deriveResources(diagnostics: MutableList<Diagnostic>): List<ResourceDefinition> {
        val dirs = try {
            source.list("resource").filter { source.list("resource/$it").isNotEmpty() }
        } catch (e: Exception) {
            diagnostics += warning(
                "resource",
                DiagnosticMessages.directoryEnumerationFailed("resource", e.message.orEmpty()),
            )
            emptyList()
        }
        if (dirs.isEmpty()) {
            diagnostics += warning("resource", DiagnosticMessages.noAvailableResource())
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

    companion object {
        /** 合成「未分组」的内部组名；身份判定走 isUngrouped，显示名由 UI 层用资源本地化 */
        const val UNGROUPED = "未分组"

        /** PI 协议里 Adb controller 的 type 字面量 */
        private const val ADB_CONTROLLER_TYPE = "Adb"
    }
}
