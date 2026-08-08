package com.aliothmoon.maafw.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.UnavailableReason
import com.aliothmoon.maafw.session.SessionMessage

/**
 * 领域/VM 层结构化语义 -> 展示文案的唯一映射点
 * 领域层与 VM 不持有 Context，跨层字符串统一在此本地化
 */

@Composable
fun UnavailableReason.localized(): String = when (this) {
    UnavailableReason.MissingDefinition -> stringResource(R.string.task_unavailable_missing)
    is UnavailableReason.ControllerMismatch ->
        stringResource(R.string.task_unavailable_controller, required.joinToString())

    is UnavailableReason.ResourceMismatch ->
        stringResource(R.string.task_unavailable_resource, required.joinToString())
}

/** 合成「未分组」组的显示名走资源；真实分组 label 是 PI 数据，原样展示 */
@Composable
fun TaskCatalogGroup.displayLabel(): String =
    if (isUngrouped) stringResource(R.string.tasks_ungrouped) else label

/** Snackbar 在 collect 协程（非组合）中消费，用 Context 版本 */
fun SessionMessage.localized(context: Context): String = when (this) {
    SessionMessage.ConfigurationLocked -> context.getString(R.string.msg_locked_while_running)
    SessionMessage.ProjectNotLoaded -> context.getString(R.string.msg_project_not_loaded)
    SessionMessage.NoExecutableTasks -> context.getString(R.string.msg_no_executable_tasks)
    is SessionMessage.TemplateNotFound -> context.getString(R.string.msg_template_not_found, templateName)
    is SessionMessage.CannotStart -> context.getString(R.string.msg_cannot_start, reason)
    is SessionMessage.CannotStop -> context.getString(R.string.msg_cannot_stop, reason)
}
