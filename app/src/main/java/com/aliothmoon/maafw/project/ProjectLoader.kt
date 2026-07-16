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
 */
class ProjectLoader(private val source: ProjectSource) {

    fun load(): ProjectLoadResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val projectInterface = loadInterface(diagnostics)

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

        for (file in files.sorted()) {
            val content = try {
                source.read(file)
            } catch (e: Exception) {
                diagnostics += error(file, "读取失败: ${e.message}")
                continue
            }
            val parsed = PiParser.parseFile(file, content)
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
        }

        validateOptionReferences(tasks, options, diagnostics)
        detectOptionCycles(options, diagnostics)
        validateTemplates(templates, tasks, diagnostics)

        val definition = ProjectDefinition(
            name = projectInterface?.name ?: source.projectName,
            version = projectInterface?.version,
            controller = ControllerDefinition(),
            resources = projectInterface?.resources
                ?.takeIf { it.isNotEmpty() }
                ?: deriveResources(diagnostics),
            tasks = tasks,
            groups = buildGroups(tasks),
            options = options,
            templates = templates,
        )
        return ProjectLoadResult.Ready(definition, diagnostics)
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

    /** 未声明 group 的任务归入客户端提供的“未分组”集合。 */
    private fun buildGroups(tasks: List<TaskDefinition>): List<TaskGroupDefinition> {
        val names = linkedSetOf<String>()
        for (task in tasks) {
            if (task.groups.isEmpty()) names += UNGROUPED else names += task.groups
        }
        return names.map { TaskGroupDefinition(it) }
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
