package com.aliothmoon.maafw.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticMessage
import com.aliothmoon.maafw.domain.DiagnosticSeverity

@Composable
fun DiagnosticSeverity.localized(): String = when (this) {
    DiagnosticSeverity.Warning -> stringResource(R.string.diagnostic_severity_warning)
    DiagnosticSeverity.Error -> stringResource(R.string.diagnostic_severity_error)
}

@Composable
fun DiagnosticMessage.localized(): String = when (this) {
    is DiagnosticMessage.InterfaceReadFailed ->
        stringResource(R.string.diagnostic_interface_read_failed, detail)

    DiagnosticMessage.MissingInterfaceVersion ->
        stringResource(R.string.diagnostic_missing_interface_version)

    is DiagnosticMessage.UnsupportedInterfaceVersion ->
        stringResource(R.string.diagnostic_unsupported_interface_version, version)

    is DiagnosticMessage.JsonParseFailed ->
        stringResource(R.string.diagnostic_json_parse_failed, detail)

    is DiagnosticMessage.TranslationJsonParseFailed ->
        stringResource(R.string.diagnostic_translation_json_parse_failed, detail)

    is DiagnosticMessage.EntryNotObject ->
        stringResource(R.string.diagnostic_entry_not_object, kind)

    is DiagnosticMessage.RequiredFieldMissing -> stringResource(
        R.string.diagnostic_required_field_missing,
        owner?.let { "$kind \"$it\"" } ?: kind,
        field,
    )

    is DiagnosticMessage.ResourcePathMissing ->
        stringResource(R.string.diagnostic_resource_path_missing, resource)

    is DiagnosticMessage.LanguagePathInvalid ->
        stringResource(R.string.diagnostic_language_path_invalid, language)

    is DiagnosticMessage.ImportReadFailed ->
        stringResource(R.string.diagnostic_import_read_failed, detail)

    DiagnosticMessage.ProjectHasNoTasks -> stringResource(R.string.diagnostic_project_has_no_tasks)
    is DiagnosticMessage.DuplicateDeclaration ->
        stringResource(R.string.diagnostic_duplicate_declaration, kind, name)

    is DiagnosticMessage.TranslationReadFailed ->
        stringResource(R.string.diagnostic_translation_read_failed, detail)

    is DiagnosticMessage.DescriptionReadFailed ->
        stringResource(R.string.diagnostic_description_read_failed, detail)

    is DiagnosticMessage.DefaultCaseMissing ->
        stringResource(R.string.diagnostic_default_case_missing, option, case)

    is DiagnosticMessage.InputHasNoFields ->
        stringResource(R.string.diagnostic_input_has_no_fields, option)

    is DiagnosticMessage.UnsupportedOptionType ->
        stringResource(R.string.diagnostic_unsupported_option_type, option, type)

    is DiagnosticMessage.InvalidOptionType ->
        stringResource(R.string.diagnostic_invalid_option_type, option, type ?: "null")

    is DiagnosticMessage.OptionCaseNotObject ->
        stringResource(R.string.diagnostic_option_case_not_object, option)

    is DiagnosticMessage.OptionCaseNameMissing ->
        stringResource(R.string.diagnostic_option_case_name_missing, option)

    is DiagnosticMessage.InvalidPipelineType ->
        stringResource(R.string.diagnostic_invalid_pipeline_type, option, input, type)

    is DiagnosticMessage.RegexCompileFailed ->
        stringResource(R.string.diagnostic_regex_compile_failed, option, input, detail)

    is DiagnosticMessage.MissingReference ->
        stringResource(R.string.diagnostic_missing_reference, kind, name)

    is DiagnosticMessage.OptionCycle -> stringResource(R.string.diagnostic_option_cycle, path)
    is DiagnosticMessage.DirectoryEnumerationFailed ->
        stringResource(R.string.diagnostic_directory_enumeration_failed, directory, detail)

    DiagnosticMessage.NoAvailableResource -> stringResource(R.string.diagnostic_no_available_resource)
    is DiagnosticMessage.ResourceSelectionMissing -> stringResource(
        R.string.diagnostic_resource_selection_missing,
        selected,
        fallback ?: stringResource(R.string.diagnostic_none),
    )

    DiagnosticMessage.ActiveConfigurationMissing ->
        stringResource(R.string.diagnostic_active_configuration_missing)

    is DiagnosticMessage.ConfiguredTaskMissing ->
        stringResource(R.string.diagnostic_configured_task_missing, task)

    DiagnosticMessage.RuntimeNoResource -> stringResource(R.string.diagnostic_runtime_no_resource)
    is DiagnosticMessage.EnabledTaskMissingDefinition ->
        stringResource(R.string.diagnostic_enabled_task_missing_definition, task)

    is DiagnosticMessage.OptionUnsetWithoutDefault ->
        stringResource(R.string.diagnostic_option_unset_without_default, option)

    is DiagnosticMessage.SelectedCaseMissing ->
        stringResource(R.string.diagnostic_selected_case_missing, option, case)

    is DiagnosticMessage.InvalidInput ->
        stringResource(R.string.diagnostic_invalid_input, option, input, detail)

    is DiagnosticMessage.IntegerConversionFailed ->
        stringResource(R.string.diagnostic_integer_conversion_failed, option, value)

    is DiagnosticMessage.BooleanConversionFailed ->
        stringResource(R.string.diagnostic_boolean_conversion_failed, option, value)
}
