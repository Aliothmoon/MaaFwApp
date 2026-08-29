package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateSource
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAppSettingsGateway : AppSettingsGateway {

    override val runMode = MutableStateFlow(RunMode.BACKGROUND)

    override suspend fun setRunMode(mode: RunMode) {
        runMode.value = mode
    }

    override val overlayControlMode = MutableStateFlow(OverlayControlMode.FLOAT_BALL)

    override suspend fun setOverlayControlMode(mode: OverlayControlMode) {
        overlayControlMode.value = mode
    }

    override val screenSaverEnabled = MutableStateFlow(false)

    override suspend fun setScreenSaverEnabled(enabled: Boolean) {
        screenSaverEnabled.value = enabled
    }

    override val closeAppAfterTask = MutableStateFlow(false)

    override suspend fun setCloseAppAfterTask(enabled: Boolean) {
        closeAppAfterTask.value = enabled
    }

    override val touchPreviewEnabled = MutableStateFlow(true)

    override suspend fun setTouchPreviewEnabled(enabled: Boolean) {
        touchPreviewEnabled.value = enabled
    }

    override val resolutionPreference = MutableStateFlow(ResolutionPreference.P720)

    override suspend fun setResolutionPreference(preference: ResolutionPreference) {
        resolutionPreference.value = preference
    }

    override val debugMode = MutableStateFlow(false)

    override suspend fun setDebugMode(enabled: Boolean) {
        debugMode.value = enabled
    }

    override val themeStyle = MutableStateFlow(ThemeStyle.DEFAULT)

    override suspend fun setThemeStyle(style: ThemeStyle) {
        themeStyle.value = style
    }

    override val wakeUnlockEnabled = MutableStateFlow(false)

    override suspend fun setWakeUnlockEnabled(enabled: Boolean) {
        wakeUnlockEnabled.value = enabled
    }

    override val wakeCredential = MutableStateFlow("")

    override suspend fun setWakeCredential(credential: String) {
        wakeCredential.value = credential.filter(Char::isDigit)
    }

    override val telemetryEnabled = MutableStateFlow(false)

    override suspend fun setTelemetryEnabled(enabled: Boolean) {
        telemetryEnabled.value = enabled
    }

    override val updateDownloadSource = MutableStateFlow(UpdateSource.MIRROR_CHYAN)

    override suspend fun setUpdateDownloadSource(source: UpdateSource) {
        updateDownloadSource.value = source
    }

    override val updateChannel = MutableStateFlow(UpdateChannel.STABLE)

    override suspend fun setUpdateChannel(channel: UpdateChannel) {
        updateChannel.value = channel
    }

    override val githubToken = MutableStateFlow("")

    override suspend fun setGithubToken(token: String) {
        githubToken.value = token.trim()
    }

    override val mirrorChyanCdk = MutableStateFlow("")

    override suspend fun setMirrorChyanCdk(cdk: String) {
        mirrorChyanCdk.value = cdk.trim()
    }
}
