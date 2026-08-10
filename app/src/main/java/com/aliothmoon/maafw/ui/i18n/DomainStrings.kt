package com.aliothmoon.maafw.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.TaskCatalogGroup

/**
 * 枚举 -> 文案的映射点
 * 带参数的文案一律由产出方构造成 `UiText`（见 `DiagnosticMessages` / `UnavailableReasons`），
 * 不在这里再做一次分派
 */

@Composable
fun DiagnosticSeverity.localized(): String = when (this) {
    DiagnosticSeverity.Warning -> stringResource(R.string.diagnostic_severity_warning)
    DiagnosticSeverity.Error -> stringResource(R.string.diagnostic_severity_error)
}

/** 合成「未分组」组的显示名走资源；真实分组 label 是 PI 数据，原样展示 */
@Composable
fun TaskCatalogGroup.displayLabel(): String =
    if (isUngrouped) stringResource(R.string.tasks_ungrouped) else label
