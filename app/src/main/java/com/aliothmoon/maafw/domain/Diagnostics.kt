package com.aliothmoon.maafw.domain

enum class DiagnosticSeverity { Warning, Error }

/**
 * 跨 ProjectLoad / Session / Runtime 的结构化诊断语义
 * source 保留技术定位；message 只携带稳定语义与参数，由 UI 统一本地化
 */
sealed interface DiagnosticMessage {
    data class InterfaceReadFailed(val detail: String) : DiagnosticMessage
    data object MissingInterfaceVersion : DiagnosticMessage
    data class UnsupportedInterfaceVersion(val version: Long) : DiagnosticMessage
    data class JsonParseFailed(val detail: String) : DiagnosticMessage
    data class TranslationJsonParseFailed(val detail: String) : DiagnosticMessage
    data class EntryNotObject(val kind: String) : DiagnosticMessage
    data class RequiredFieldMissing(
        val kind: String,
        val field: String,
        val owner: String? = null,
    ) : DiagnosticMessage

    data class ResourcePathMissing(val resource: String) : DiagnosticMessage

    /** PI 未声明 Adb controller：该 PI 不面向 Android，带 controller 限定的任务都会不适用 */
    data object NoAdbController : DiagnosticMessage
    data class LanguagePathInvalid(val language: String) : DiagnosticMessage
    data class ImportReadFailed(val detail: String) : DiagnosticMessage
    data object ProjectHasNoTasks : DiagnosticMessage
    data class DuplicateDeclaration(val kind: String, val name: String) : DiagnosticMessage
    data class TranslationReadFailed(val detail: String) : DiagnosticMessage
    data class DescriptionReadFailed(val detail: String) : DiagnosticMessage
    data class DefaultCaseMissing(val option: String, val case: String) : DiagnosticMessage
    data class InputHasNoFields(val option: String) : DiagnosticMessage
    data class UnsupportedOptionType(val option: String, val type: String) : DiagnosticMessage
    data class InvalidOptionType(val option: String, val type: String?) : DiagnosticMessage
    data class OptionCaseNotObject(val option: String) : DiagnosticMessage
    data class OptionCaseNameMissing(val option: String) : DiagnosticMessage
    data class InvalidPipelineType(val option: String, val input: String, val type: String) : DiagnosticMessage
    data class RegexCompileFailed(val option: String, val input: String, val detail: String) : DiagnosticMessage
    data class MissingReference(val kind: String, val name: String) : DiagnosticMessage
    data class OptionCycle(val path: String) : DiagnosticMessage
    data class DirectoryEnumerationFailed(val directory: String, val detail: String) : DiagnosticMessage
    data object NoAvailableResource : DiagnosticMessage
    data class ResourceSelectionMissing(val selected: String, val fallback: String?) : DiagnosticMessage
    data object ActiveConfigurationMissing : DiagnosticMessage
    data class ConfiguredTaskMissing(val task: String) : DiagnosticMessage
    data object RuntimeNoResource : DiagnosticMessage
    data class EnabledTaskMissingDefinition(val task: String) : DiagnosticMessage
    data class OptionUnsetWithoutDefault(val option: String) : DiagnosticMessage
    data class SelectedCaseMissing(val option: String, val case: String) : DiagnosticMessage
    data class InvalidInput(
        val option: String,
        val input: String,
        val detail: String,
    ) : DiagnosticMessage

    data class IntegerConversionFailed(val option: String, val value: String) : DiagnosticMessage
    data class BooleanConversionFailed(val option: String, val value: String) : DiagnosticMessage
}

data class Diagnostic(
    val severity: DiagnosticSeverity,
    val source: String,
    val message: DiagnosticMessage,
) {
    companion object {
        fun error(source: String, message: DiagnosticMessage) =
            Diagnostic(DiagnosticSeverity.Error, source, message)

        fun warning(source: String, message: DiagnosticMessage) =
            Diagnostic(DiagnosticSeverity.Warning, source, message)
    }
}
