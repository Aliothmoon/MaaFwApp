package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf

/** 特权侧 [PrepareFailureCode] → 文案；认不出的码（两端版本不一致）原样带出 */
internal fun prepareFailureText(code: String?): UiText = when (code) {
    PrepareFailureCode.FRAMEWORK_NOT_LOADED -> uiTextOf(R.string.msg_prepare_framework_not_loaded)
    PrepareFailureCode.BRIDGE_NOT_LOADED -> uiTextOf(R.string.msg_prepare_bridge_not_loaded)
    PrepareFailureCode.CAPTURE_NOT_STARTED -> uiTextOf(R.string.msg_prepare_capture_not_started)
    PrepareFailureCode.DISPLAY_NOT_STARTED -> uiTextOf(R.string.msg_prepare_display_not_started)
    PrepareFailureCode.RESOURCE_CREATE, PrepareFailureCode.RESOURCE_LOAD -> uiTextOf(R.string.msg_prepare_resource)
    PrepareFailureCode.CONTROLLER_CREATE, PrepareFailureCode.CONTROLLER_CONNECT ->
        uiTextOf(R.string.msg_prepare_controller)
    PrepareFailureCode.TASKER_CREATE, PrepareFailureCode.TASKER_BIND -> uiTextOf(R.string.msg_prepare_tasker)
    PrepareFailureCode.AGENT_SINKS -> uiTextOf(R.string.msg_prepare_agent_sinks)
    null, "" -> uiTextOf(R.string.msg_fail_default)
    else -> uiTextFromFramework(code)
}
