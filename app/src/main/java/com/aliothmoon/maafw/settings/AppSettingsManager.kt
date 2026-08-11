package com.aliothmoon.maafw.settings
import com.aliothmoon.maafw.MaaDispatchers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.theme.ThemeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * app 设置的唯一读写入口
 *
 * 各项以 StateFlow 暴露，`.value` 在构造后即为盘上真实值——`initialSettings` 阻塞读了一次首值。
 * 需要它是因为 [com.aliothmoon.maafw.privileged.RemoteServiceManager] 要的是同步的
 * `() -> RemoteBackend`，拿不到挂起点
 */
class AppSettingsManager(private val context: Context) : AppSettingsGateway {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
    }

    val settings: Flow<AppSettings> = with(AppSettingsSchema) { context.dataStore.flow }

    // 阻塞读首值，保证下面各 StateFlow 的 .value 不是默认值
    private val initialSettings: AppSettings = runBlocking { settings.first() }

    val startupBackend: StateFlow<RemoteBackend> = settings
        .map { parseBackend(it.startupBackend) }
        .stateIn(scope, SharingStarted.Eagerly, parseBackend(initialSettings.startupBackend))

    val skipShizukuCheck: StateFlow<Boolean> = settings
        .map { it.skipShizukuCheck.toBoolean() }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.skipShizukuCheck.toBoolean())

    val shizukuLaunchPackage: StateFlow<String> = settings
        .map { it.shizukuLaunchPackage }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.shizukuLaunchPackage)

    val shizukuShortcutEnabled: StateFlow<Boolean> = settings
        .map { it.shizukuShortcutEnabled.toBoolean() }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.shizukuShortcutEnabled.toBoolean())

    override val runMode: StateFlow<RunMode> = settings
        .map { parseRunMode(it.runMode) }
        .stateIn(scope, SharingStarted.Eagerly, parseRunMode(initialSettings.runMode))

    override val overlayControlMode: StateFlow<OverlayControlMode> = settings
        .map { parseOverlayMode(it.overlayControlMode) }
        .stateIn(scope, SharingStarted.Eagerly, parseOverlayMode(initialSettings.overlayControlMode))

    override val screenSaverEnabled: StateFlow<Boolean> = settings
        .map { it.screenSaverEnabled.toBoolean() }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.screenSaverEnabled.toBoolean())

    override val resolutionPreference: StateFlow<ResolutionPreference> = settings
        .map { parseResolutionPreference(it.resolutionPreference) }
        .stateIn(scope, SharingStarted.Eagerly, parseResolutionPreference(initialSettings.resolutionPreference))

    override val debugMode: StateFlow<Boolean> = settings
        .map { it.debugMode.toBoolean() }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.debugMode.toBoolean())

    override val themeStyle: StateFlow<ThemeStyle> = settings
        .map { parseThemeStyle(it.themeStyle) }
        .stateIn(scope, SharingStarted.Eagerly, parseThemeStyle(initialSettings.themeStyle))

    override val wakeUnlockEnabled: StateFlow<Boolean> = settings
        .map { it.wakeUnlockEnabled.toBoolean() }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.wakeUnlockEnabled.toBoolean())

    override val wakeCredential: StateFlow<String> = settings
        .map { it.wakeCredential }
        .stateIn(scope, SharingStarted.Eagerly, initialSettings.wakeCredential)

    suspend fun setStartupBackend(backend: RemoteBackend) = with(AppSettingsSchema) {
        context.dataStore.edit { it[startupBackend] = backend.name }
    }

    suspend fun setSkipShizukuCheck(skip: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[skipShizukuCheck] = skip.toString() }
    }

    suspend fun setShizukuLaunchPackage(packageName: String) = with(AppSettingsSchema) {
        context.dataStore.edit { it[shizukuLaunchPackage] = packageName }
    }

    suspend fun setShizukuShortcutEnabled(enabled: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[shizukuShortcutEnabled] = enabled.toString() }
    }

    override suspend fun setRunMode(mode: RunMode): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[runMode] = mode.name }
    }

    override suspend fun setOverlayControlMode(mode: OverlayControlMode): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[overlayControlMode] = mode.name }
    }

    override suspend fun setScreenSaverEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[screenSaverEnabled] = enabled.toString() }
    }

    override suspend fun setResolutionPreference(preference: ResolutionPreference): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[resolutionPreference] = preference.name }
    }

    override suspend fun setDebugMode(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[debugMode] = enabled.toString() }
    }

    override suspend fun setThemeStyle(style: ThemeStyle): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[themeStyle] = style.name }
    }

    override suspend fun setWakeUnlockEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[wakeUnlockEnabled] = enabled.toString() }
    }

    /** 只留数字：注入按键只能打出 0-9，图案与密码锁屏的面板模拟不出来 */
    override suspend fun setWakeCredential(credential: String): Unit = with(AppSettingsSchema) {
        val digits = credential.filter(Char::isDigit)
        context.dataStore.edit { it[wakeCredential] = digits }
    }

    /** 盘上是历史遗留或手改的非法值时回落默认，不让设置读取本身抛异常 */
    private fun parseBackend(raw: String): RemoteBackend =
        runCatching { RemoteBackend.valueOf(raw) }.getOrDefault(RemoteBackend.SHIZUKU)

    private fun parseRunMode(raw: String): RunMode =
        runCatching { RunMode.valueOf(raw) }.getOrDefault(RunMode.BACKGROUND)

    private fun parseOverlayMode(raw: String): OverlayControlMode =
        runCatching { OverlayControlMode.valueOf(raw) }.getOrDefault(OverlayControlMode.FLOAT_BALL)

    private fun parseResolutionPreference(raw: String): ResolutionPreference =
        runCatching { ResolutionPreference.valueOf(raw) }.getOrDefault(ResolutionPreference.P720)

    private fun parseThemeStyle(raw: String): ThemeStyle =
        runCatching { ThemeStyle.valueOf(raw) }.getOrDefault(ThemeStyle.DEFAULT)
}
