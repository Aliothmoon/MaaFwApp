package com.aliothmoon.maafw.domain

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

enum class DiagnosticSeverity { Warning, Error }

/**
 * 跨 ProjectLoad / Session / Runtime 的诊断
 * source 保留技术定位；message 是延迟解析的展示文本，切语言后自然出新文案
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val source: String,
    val message: UiText,
) {
    companion object {
        fun error(source: String, message: UiText) =
            Diagnostic(DiagnosticSeverity.Error, source, message)

        fun warning(source: String, message: UiText) =
            Diagnostic(DiagnosticSeverity.Warning, source, message)
    }
}

/**
 * 诊断文案的构造点
 *
 * 产出方（Parser / Loader / Resolver / Builder）散在四个文件里，若各自写
 * `uiTextOf(R.string.x, a, b)`，资源 id 与参数顺序就要在四处各记一遍，改一个占位符
 * 得全仓翻。收在这里之后，调用点只看得见有名字的参数
 *
 * 临时的、一次性的诊断不必进这里，直接 `Diagnostic.error(source, uiTextOf(...))` 即可
 */
object DiagnosticMessages {

    // ── PI 解析 ──

    fun interfaceReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_interface_read_failed, detail)

    fun missingInterfaceVersion(): UiText = uiTextOf(R.string.diagnostic_missing_interface_version)

    fun unsupportedInterfaceVersion(version: Long): UiText =
        uiTextOf(R.string.diagnostic_unsupported_interface_version, version)

    fun jsonParseFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_json_parse_failed, detail)

    fun translationJsonParseFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_translation_json_parse_failed, detail)

    fun entryNotObject(kind: String): UiText = uiTextOf(R.string.diagnostic_entry_not_object, kind)

    /** owner 非空时把它并进 kind 一起显示，如 `task "Foo"` */
    fun requiredFieldMissing(kind: String, field: String, owner: String? = null): UiText =
        uiTextOf(
            R.string.diagnostic_required_field_missing,
            owner?.let { "$kind \"$it\"" } ?: kind,
            field,
        )

    fun resourcePathMissing(resource: String): UiText =
        uiTextOf(R.string.diagnostic_resource_path_missing, resource)

    /** PI 未声明 Adb controller：该 PI 不面向 Android，带 controller 限定的任务都会不适用 */
    fun noAdbController(): UiText = uiTextOf(R.string.diagnostic_no_adb_controller)

    fun languagePathInvalid(language: String): UiText =
        uiTextOf(R.string.diagnostic_language_path_invalid, language)

    fun importReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_import_read_failed, detail)

    fun projectHasNoTasks(): UiText = uiTextOf(R.string.diagnostic_project_has_no_tasks)

    fun duplicateDeclaration(kind: String, name: String): UiText =
        uiTextOf(R.string.diagnostic_duplicate_declaration, kind, name)

    fun translationReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_translation_read_failed, detail)

    fun descriptionReadFailed(detail: String): UiText =
        uiTextOf(R.string.diagnostic_description_read_failed, detail)

    fun directoryEnumerationFailed(directory: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_directory_enumeration_failed, directory, detail)

    fun missingReference(kind: String, name: String): UiText =
        uiTextOf(R.string.diagnostic_missing_reference, kind, name)

    // ── option 声明 ──

    fun defaultCaseMissing(option: String, case: String): UiText =
        uiTextOf(R.string.diagnostic_default_case_missing, option, case)

    fun inputHasNoFields(option: String): UiText =
        uiTextOf(R.string.diagnostic_input_has_no_fields, option)

    fun unsupportedOptionType(option: String, type: String): UiText =
        uiTextOf(R.string.diagnostic_unsupported_option_type, option, type)

    fun invalidOptionType(option: String, type: String?): UiText =
        uiTextOf(R.string.diagnostic_invalid_option_type, option, type ?: "null")

    fun optionCaseNotObject(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_case_not_object, option)

    fun optionCaseNameMissing(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_case_name_missing, option)

    fun invalidPipelineType(option: String, input: String, type: String): UiText =
        uiTextOf(R.string.diagnostic_invalid_pipeline_type, option, input, type)

    fun regexCompileFailed(option: String, input: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_regex_compile_failed, option, input, detail)

    fun optionCycle(path: String): UiText = uiTextOf(R.string.diagnostic_option_cycle, path)

    // ── 会话解析 ──

    fun noAvailableResource(): UiText = uiTextOf(R.string.diagnostic_no_available_resource)

    /** fallback 为空时占位符仍要有东西可填，用「无」而不是空串 */
    fun resourceSelectionMissing(selected: String, fallback: String?): UiText =
        uiTextOf(
            R.string.diagnostic_resource_selection_missing,
            selected,
            // args 是 Any?，裸 String 与 UiText 混着传都行，解析时各走各的分支
            fallback ?: uiTextOf(R.string.diagnostic_none),
        )

    fun activeConfigurationMissing(): UiText =
        uiTextOf(R.string.diagnostic_active_configuration_missing)

    fun configuredTaskMissing(task: String): UiText =
        uiTextOf(R.string.diagnostic_configured_task_missing, task)

    // ── 运行时编译 ──

    fun runtimeNoResource(): UiText = uiTextOf(R.string.diagnostic_runtime_no_resource)

    fun enabledTaskMissingDefinition(task: String): UiText =
        uiTextOf(R.string.diagnostic_enabled_task_missing_definition, task)

    fun optionUnsetWithoutDefault(option: String): UiText =
        uiTextOf(R.string.diagnostic_option_unset_without_default, option)

    fun selectedCaseMissing(option: String, case: String): UiText =
        uiTextOf(R.string.diagnostic_selected_case_missing, option, case)

    fun invalidInput(option: String, input: String, detail: String): UiText =
        uiTextOf(R.string.diagnostic_invalid_input, option, input, detail)

    fun integerConversionFailed(option: String, value: String): UiText =
        uiTextOf(R.string.diagnostic_integer_conversion_failed, option, value)

    fun booleanConversionFailed(option: String, value: String): UiText =
        uiTextOf(R.string.diagnostic_boolean_conversion_failed, option, value)
}
