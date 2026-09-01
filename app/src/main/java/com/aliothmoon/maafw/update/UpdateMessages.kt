package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

/**
 * 更新失败文案：reason 枚举自带 UiText，这里只拼接动态细节。
 * UI 层（SettingsScreen / 通知）统一从这里取，不许再拼 `reason.name`
 */
fun UpdateCheckResult.message(): UiText? = when (this) {
    is UpdateCheckResult.UpdateAvailable -> null
    is UpdateCheckResult.UpToDate -> null
    is UpdateCheckResult.SourceFailed -> reason.message.withDetail(detail)
}

fun UpdateResolveResult.message(): UiText? = when (this) {
    is UpdateResolveResult.Resolved -> null
    is UpdateResolveResult.Failed -> reason.message.withDetail(detail)
}

fun UpdateDownloadResult.Failed.message(): UiText = reason.message.withDetail(detail)

fun SystemApkInstaller.Result.Failed.message(): UiText = reason.message

private fun UiText.withDetail(detail: UiText?): UiText =
    if (detail == null || detail is UiText.Empty) this
    else uiTextOf(R.string.update_fail_with_detail, this, detail)
