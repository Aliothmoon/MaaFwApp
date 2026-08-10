package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.RunMode
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAppSettingsGateway : AppSettingsGateway {

    override val runMode = MutableStateFlow(RunMode.BACKGROUND)

    override suspend fun setRunMode(mode: RunMode) {
        runMode.value = mode
    }
}
