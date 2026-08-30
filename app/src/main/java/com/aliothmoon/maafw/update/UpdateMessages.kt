package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

/**
 * 更新失败文案：reason 枚举自带资源（`messageRes`），这里只负责拼接动态细节。
 * UI 层（SettingsScreen / 通知）统一从这里取，不许再拼 `reason.name`
 */
fun UpdateCheckResult.errorMessage(): UiText? = when (this) {
    is UpdateCheckResult.UpdateAvailable -> null
    is UpdateCheckResult.UpToDate -> null
    is UpdateCheckResult.SourceFailed -> reason.message.withDetail(detail)
}

fun UpdateResolveResult.errorMessage(): UiText? = when (this) {
    is UpdateResolveResult.Resolved -> null
    is UpdateResolveResult.Failed -> reason.message.withDetail(detail)
}

fun UpdateDownloadResult.Failed.errorMessage(): UiText = reason.messageRes.withDetail(detail)

fun SystemApkInstaller.Result.Failed.errorMessage(): UiText = reason.messageRes.withDetail(null)

private fun Int.withDetail(detail: UiText?): UiText {
    val reason = uiTextOf(this)
    return if (detail == null || detail is UiText.Empty) {
        reason
    } else {
        uiTextOf(R.string.update_fail_with_detail, reason, detail)
    }
}
