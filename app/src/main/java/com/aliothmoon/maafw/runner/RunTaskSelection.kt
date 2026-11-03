package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunConfigurationId

/** One-run task range; it never changes the saved configuration. */
data class RunTaskSelection(
    val configurationId: RunConfigurationId,
    val instanceId: String,
    val mode: RunTaskSelectionMode,
)

enum class RunTaskSelectionMode {
    Current,
    CurrentAndFollowing,
}
