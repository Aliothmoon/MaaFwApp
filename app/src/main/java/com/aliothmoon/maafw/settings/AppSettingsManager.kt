package com.aliothmoon.maafw.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.domain.EventNotificationLevel
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.notification.live.LiveBackend
import com.aliothmoon.maafw.runner.ResolutionPreset
import com.aliothmoon.maafw.runner.ResolutionPresets
import com.aliothmoon.maafw.runner.RunDurationLimit
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.theme.UiScale
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateSource
import com.aliothmoon.maafw.wallpaper.WallpaperSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * app 设置的唯一读写入口
 *
 * 各项以 StateFlow 暴露。读盘是异步的，[loaded] 置位之前 `.value` 还是 schema 默认值——
 * 同步 `.value` 是 [com.aliothmoon.maafw.privileged.RemoteServiceManager] 那条链要的
 * （它收的是 `() -> RemoteBackend`，没有挂起点），所以读盘不能省，只能挪到构造之外
 *
 * **凡是在启动早期同步读 `.value` 的调用方都必须先等 [loaded]**：早读一步拿到的是
 * 默认值，Root 用户会被当成 Shizuku。启动首屏与 `MaaFwApp.postCreate` 都挂在这上面
 */
class AppSettingsManager(private val context: Context) : AppSettingsGateway {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)

    companion object {
        // 文件坏了不兜就抛 CorruptionException，init 里的 collect 没人接，进程每次启动都崩；
        // 回落默认值的代价是用户要重新选一遍后端等设置
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "app_settings",
            corruptionHandler = ReplaceFileCorruptionHandler {
                Timber.e(it, "App settings file corrupted; resetting to defaults")
                emptyPreferences()
            },
            produceMigrations = { listOf(WakeUnlockTypeMigration) },
        )

        /**
         * 随配置导出、导入的设置：和这台设备无关、也不含凭据的那些
         *
         * 不在列：提权后端与 Shizuku 几项（换台设备不一定有 Root / Shizuku）、虚拟屏分辨率（看屏幕）、
         * 运行通知样式（超级岛、实时更新看 ROM）、背景图（图不进文件）、唤醒解锁、Mirror酱 CDK。
         * 不在列的项导入时一律不碰，本机填好的不会被清掉
         */
        private val PORTABLE_SETTINGS: List<Pair<Preferences.Key<String>, (AppSettings) -> String>> =
            with(AppSettingsSchema) {
                listOf(
                    runMode to AppSettings::runMode,
                    overlayControlMode to AppSettings::overlayControlMode,
                    screenSaverEnabled to AppSettings::screenSaverEnabled,
                    closeAppAfterTask to AppSettings::closeAppAfterTask,
                    touchPreviewEnabled to AppSettings::touchPreviewEnabled,
                    debugMode to AppSettings::debugMode,
                    saveOnError to AppSettings::saveOnError,
                    themeStyle to AppSettings::themeStyle,
                    uiScale to AppSettings::uiScale,
                    wallpaperImageAlpha to AppSettings::wallpaperImageAlpha,
                    wallpaperScrim to AppSettings::wallpaperScrim,
                    wallpaperBlur to AppSettings::wallpaperBlur,
                    eventNotificationLevel to AppSettings::eventNotificationLevel,
                    runDurationLimitEnabled to AppSettings::runDurationLimitEnabled,
                    runDurationLimitMinutes to AppSettings::runDurationLimitMinutes,
                    telemetryEnabled to AppSettings::telemetryEnabled,
                    autoCheckUpdate to AppSettings::autoCheckUpdate,
                    autoDownloadUpdate to AppSettings::autoDownloadUpdate,
                    updateChannel to AppSettings::updateChannel,
                    updateSource to AppSettings::updateSource,
                    pipOnHome to AppSettings::pipOnHome,
                )
            }
    }

    val settings: Flow<AppSettings> = with(AppSettingsSchema) { context.dataStore.flow }

    private val defaults = AppSettings()

    private val _loaded = MutableStateFlow(false)

    /**
     * 首次读盘是否已落到下面各 StateFlow 上；置位后 `.value` 才是盘上的值
     *
     * 现有等待点包括启动首屏（`MainActivity`）、`MaaFwApp.postCreate`（`RemoteAccessCoordinator`
     * 一初始化就同步读 startupBackend）、`ScheduleExecutionService.handleTrigger`（投递前要 runMode）
     * 和 `ScheduleReceiver` 服务启动失败后的兜底重排（要用 runMode 选闹钟提前量）
     */
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _startupBackend = MutableStateFlow(parseBackend(defaults.startupBackend))
    val startupBackend: StateFlow<RemoteBackend> = _startupBackend.asStateFlow()

    private val _skipShizukuCheck = MutableStateFlow(defaults.skipShizukuCheck.toBoolean())
    val skipShizukuCheck: StateFlow<Boolean> = _skipShizukuCheck.asStateFlow()

    private val _shizukuLaunchPackage = MutableStateFlow(defaults.shizukuLaunchPackage)
    val shizukuLaunchPackage: StateFlow<String> = _shizukuLaunchPackage.asStateFlow()

    private val _shizukuShortcutEnabled = MutableStateFlow(defaults.shizukuShortcutEnabled.toBoolean())
    val shizukuShortcutEnabled: StateFlow<Boolean> = _shizukuShortcutEnabled.asStateFlow()

    private val _runMode = MutableStateFlow(parseRunMode(defaults.runMode))
    override val runMode: StateFlow<RunMode> = _runMode.asStateFlow()

    private val _overlayControlMode = MutableStateFlow(parseOverlayMode(defaults.overlayControlMode))
    override val overlayControlMode: StateFlow<OverlayControlMode> = _overlayControlMode.asStateFlow()

    private val _screenSaverEnabled = MutableStateFlow(defaults.screenSaverEnabled.toBoolean())
    override val screenSaverEnabled: StateFlow<Boolean> = _screenSaverEnabled.asStateFlow()

    private val _closeAppAfterTask = MutableStateFlow(defaults.closeAppAfterTask.toBoolean())
    override val closeAppAfterTask: StateFlow<Boolean> = _closeAppAfterTask.asStateFlow()

    private val _touchPreviewEnabled = MutableStateFlow(defaults.touchPreviewEnabled.toBoolean())
    override val touchPreviewEnabled: StateFlow<Boolean> = _touchPreviewEnabled.asStateFlow()

    private val _eventNotificationLevel =
        MutableStateFlow(parseEventNotificationLevel(defaults.eventNotificationLevel))
    val eventNotificationLevel: StateFlow<EventNotificationLevel> = _eventNotificationLevel.asStateFlow()

    private val _liveBackend = MutableStateFlow(parseLiveBackend(defaults.liveBackend))

    /** 运行通知的展示方式；null 是没选过，见 [com.aliothmoon.maafw.notification.live.LiveBackends.resolve] */
    val liveBackend: StateFlow<LiveBackend?> = _liveBackend.asStateFlow()

    private val _resolutionPreset = MutableStateFlow(ResolutionPresets.resolve(defaults.resolutionPreset))
    override val resolutionPreset: StateFlow<ResolutionPreset> = _resolutionPreset.asStateFlow()

    private val _debugMode = MutableStateFlow(defaults.debugMode.toBoolean())
    override val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _saveOnError = MutableStateFlow(defaults.saveOnError.toBoolean())
    override val saveOnError: StateFlow<Boolean> = _saveOnError.asStateFlow()

    private val _themeStyle = MutableStateFlow(parseThemeStyle(defaults.themeStyle))
    override val themeStyle: StateFlow<ThemeStyle> = _themeStyle.asStateFlow()

    private val _uiScale = MutableStateFlow(UiScale.parse(defaults.uiScale))
    override val uiScale: StateFlow<Int> = _uiScale.asStateFlow()

    private val _wallpaper = MutableStateFlow(parseWallpaper(defaults))
    val wallpaper: StateFlow<WallpaperSettings> = _wallpaper.asStateFlow()

    private val _wakeUnlockType = MutableStateFlow(parseWakeUnlockType(defaults.wakeUnlockType))
    override val wakeUnlockType: StateFlow<String> = _wakeUnlockType.asStateFlow()

    private val _wakeCredential = MutableStateFlow(defaults.wakeCredential)
    override val wakeCredential: StateFlow<String> = _wakeCredential.asStateFlow()

    private val _runDurationLimitEnabled =
        MutableStateFlow(defaults.runDurationLimitEnabled.toBoolean())
    override val runDurationLimitEnabled: StateFlow<Boolean> = _runDurationLimitEnabled.asStateFlow()

    private val _runDurationLimitMinutes =
        MutableStateFlow(RunDurationLimit.parse(defaults.runDurationLimitMinutes))
    override val runDurationLimitMinutes: StateFlow<Int> = _runDurationLimitMinutes.asStateFlow()

    private val _telemetryEnabled = MutableStateFlow(defaults.telemetryEnabled.toBoolean())
    override val telemetryEnabled: StateFlow<Boolean> = _telemetryEnabled.asStateFlow()

    private val _autoCheckUpdate = MutableStateFlow(defaults.autoCheckUpdate.toBoolean())
    override val autoCheckUpdate: StateFlow<Boolean> = _autoCheckUpdate.asStateFlow()

    private val _autoDownloadUpdate = MutableStateFlow(defaults.autoDownloadUpdate.toBoolean())
    override val autoDownloadUpdate: StateFlow<Boolean> = _autoDownloadUpdate.asStateFlow()

    private val _updateChannel = MutableStateFlow(parseUpdateChannel(defaults.updateChannel))
    override val updateChannel: StateFlow<UpdateChannel> = _updateChannel.asStateFlow()

    private val _updateSource = MutableStateFlow(parseUpdateSource(defaults.updateSource))
    override val updateSource: StateFlow<UpdateSource> = _updateSource.asStateFlow()

    private val _pipOnHome = MutableStateFlow(defaults.pipOnHome.toBoolean())
    override val pipOnHome: StateFlow<Boolean> = _pipOnHome.asStateFlow()

    private val _mirrorchyanCdk = MutableStateFlow(defaults.mirrorchyanCdk)
    override val mirrorchyanCdk: StateFlow<String> = _mirrorchyanCdk.asStateFlow()

    init {
        // 一处 collect 铺开到各字段，而不是每个字段各起一条 stateIn：
        // 那样 loaded 置位与各字段拿到首值是两件并发的事，早读的人仍可能读到默认值
        scope.launch {
            settings.collect { s ->
                _startupBackend.value = parseBackend(s.startupBackend)
                _skipShizukuCheck.value = s.skipShizukuCheck.toBoolean()
                _shizukuLaunchPackage.value = s.shizukuLaunchPackage
                _shizukuShortcutEnabled.value = s.shizukuShortcutEnabled.toBoolean()
                _runMode.value = parseRunMode(s.runMode)
                _overlayControlMode.value = parseOverlayMode(s.overlayControlMode)
                _screenSaverEnabled.value = s.screenSaverEnabled.toBoolean()
                _closeAppAfterTask.value = s.closeAppAfterTask.toBoolean()
                _touchPreviewEnabled.value = s.touchPreviewEnabled.toBoolean()
                _resolutionPreset.value = ResolutionPresets.resolve(s.resolutionPreset)
                _debugMode.value = s.debugMode.toBoolean()
                _saveOnError.value = s.saveOnError.toBoolean()
                _themeStyle.value = parseThemeStyle(s.themeStyle)
                _uiScale.value = UiScale.parse(s.uiScale)
                _wallpaper.value = parseWallpaper(s)
                _eventNotificationLevel.value = parseEventNotificationLevel(s.eventNotificationLevel)
                _liveBackend.value = parseLiveBackend(s.liveBackend)
                _wakeUnlockType.value = parseWakeUnlockType(s.wakeUnlockType)
                _wakeCredential.value = s.wakeCredential
                _runDurationLimitEnabled.value = s.runDurationLimitEnabled.toBoolean()
                _runDurationLimitMinutes.value = RunDurationLimit.parse(s.runDurationLimitMinutes)
                _telemetryEnabled.value = s.telemetryEnabled.toBoolean()
                _autoCheckUpdate.value = s.autoCheckUpdate.toBoolean()
                _autoDownloadUpdate.value = s.autoDownloadUpdate.toBoolean()
                _updateChannel.value = parseUpdateChannel(s.updateChannel)
                _updateSource.value = parseUpdateSource(s.updateSource)
                _pipOnHome.value = s.pipOnHome.toBoolean()
                _mirrorchyanCdk.value = s.mirrorchyanCdk
                // 必须是最后一行：置位即宣告上面全部就位
                _loaded.value = true
            }
        }
    }

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

    override suspend fun setCloseAppAfterTask(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[closeAppAfterTask] = enabled.toString() }
    }

    override suspend fun setTouchPreviewEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[touchPreviewEnabled] = enabled.toString() }
    }

    override suspend fun setResolutionPreset(preset: ResolutionPreset): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[resolutionPreset] = preset.id }
    }

    override suspend fun setDebugMode(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[debugMode] = enabled.toString() }
    }

    override suspend fun setSaveOnError(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[saveOnError] = enabled.toString() }
    }

    override suspend fun setThemeStyle(style: ThemeStyle): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[themeStyle] = style.name }
    }

    override suspend fun setUiScale(scale: Int): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[uiScale] = UiScale.format(scale) }
    }

    /** 开关与令牌一起写：换图成功才启用，关闭时清令牌，两步分开写会让中间态去解码一张不存在的图 */
    suspend fun setWallpaperState(enabled: Boolean, token: String) = with(AppSettingsSchema) {
        context.dataStore.edit {
            it[wallpaperEnabled] = enabled.toString()
            it[wallpaperToken] = token
        }
    }

    suspend fun setWallpaperEnabled(enabled: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[wallpaperEnabled] = enabled.toString() }
    }

    suspend fun setWallpaperImageAlpha(percent: Int) = with(AppSettingsSchema) {
        context.dataStore.edit { it[wallpaperImageAlpha] = percent.coerceIn(0, 100).toString() }
    }

    suspend fun setWallpaperScrim(percent: Int) = with(AppSettingsSchema) {
        context.dataStore.edit { it[wallpaperScrim] = percent.coerceIn(0, 100).toString() }
    }

    suspend fun setWallpaperBlur(percent: Int) = with(AppSettingsSchema) {
        context.dataStore.edit { it[wallpaperBlur] = percent.coerceIn(0, 100).toString() }
    }

    suspend fun setEventNotificationLevel(level: EventNotificationLevel) = with(AppSettingsSchema) {
        context.dataStore.edit { it[eventNotificationLevel] = level.name }
    }

    suspend fun setLiveBackend(backend: LiveBackend?) = with(AppSettingsSchema) {
        context.dataStore.edit { it[liveBackend] = backend?.name.orEmpty() }
    }

    override suspend fun setWakeUnlockType(type: String): Unit = with(AppSettingsSchema) {
        if (type !in UnlockCredential.TYPES) return
        context.dataStore.edit { it[wakeUnlockType] = type }
    }

    /** 只留数字：注入按键只能打出 0-9，图案与密码锁屏的面板模拟不出来 */
    override suspend fun setWakeCredential(credential: String): Unit = with(AppSettingsSchema) {
        val digits = credential.filter(Char::isDigit)
        context.dataStore.edit { it[wakeCredential] = digits }
    }

    override suspend fun setRunDurationLimitEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[runDurationLimitEnabled] = enabled.toString() }
    }

    override suspend fun setRunDurationLimitMinutes(minutes: Int): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[runDurationLimitMinutes] = RunDurationLimit.normalize(minutes).toString() }
    }

    override suspend fun setTelemetryEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[telemetryEnabled] = enabled.toString() }
    }

    override suspend fun setAutoCheckUpdate(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[autoCheckUpdate] = enabled.toString() }
    }

    override suspend fun setAutoDownloadUpdate(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[autoDownloadUpdate] = enabled.toString() }
    }

    override suspend fun setUpdateChannel(channel: UpdateChannel): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[updateChannel] = channel.name }
    }

    override suspend fun setUpdateSource(source: UpdateSource): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[updateSource] = source.name }
    }

    override suspend fun setPipOnHome(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[pipOnHome] = enabled.toString() }
    }

    override suspend fun setMirrorchyanCdk(cdk: String): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[mirrorchyanCdk] = cdk.trim() }
    }

    /** 导出配置带走的那几项，键是 DataStore 里的键名；没写过的项按默认值导出，文件里一眼看得全 */
    suspend fun portableSettings(): Map<String, String> {
        val current = settings.first()
        return PORTABLE_SETTINGS.associate { (key, read) -> key.name to read(current) }
    }

    /**
     * 恢复备份里的设置：只认 [PORTABLE_SETTINGS] 里的键，文件里没有的项不动。
     * 值照旧以文本落盘，非法值读的时候各自回落默认，与手改 DataStore 同一套兜底
     */
    suspend fun importPortableSettings(values: Map<String, String>) {
        context.dataStore.edit { prefs ->
            PORTABLE_SETTINGS.forEach { (key, _) -> values[key.name]?.let { prefs[key] = it } }
        }
    }

    private fun parseWallpaper(s: AppSettings) = WallpaperSettings(
        enabled = s.wallpaperEnabled.toBoolean(),
        token = s.wallpaperToken,
        imageAlpha = parsePercent(s.wallpaperImageAlpha, 80),
        scrim = parsePercent(s.wallpaperScrim, 25),
        blur = parsePercent(s.wallpaperBlur, 0),
    )

    private fun parsePercent(raw: String, default: Int): Int = raw.toIntOrNull()?.coerceIn(0, 100) ?: default

    /** 盘上是历史遗留或手改的非法值时回落默认，不让设置读取本身抛异常 */
    private fun parseBackend(raw: String): RemoteBackend =
        runCatching { RemoteBackend.valueOf(raw) }.getOrDefault(RemoteBackend.SHIZUKU)

    /**
     * 没选过（空串）一律「无密码」，不按有没有 PIN 推断：推断值会随 PIN 输入框的增删来回跳。
     * 老版本「开关 + PIN」的用户由 [WakeUnlockTypeMigration] 写成确定值
     */
    private fun parseWakeUnlockType(raw: String): String =
        if (raw in UnlockCredential.TYPES) raw else UnlockCredential.TYPE_SWIPE

    private fun parseRunMode(raw: String): RunMode = RunMode.resolve(raw, BuildConfig.MAFW_FOREGROUND_ALLOWED)

    private fun parseOverlayMode(raw: String): OverlayControlMode =
        runCatching { OverlayControlMode.valueOf(raw) }.getOrDefault(OverlayControlMode.FLOAT_BALL)

    private fun parseThemeStyle(raw: String): ThemeStyle =
        runCatching { ThemeStyle.valueOf(raw) }.getOrDefault(ThemeStyle.DEFAULT)

    private fun parseLiveBackend(raw: String): LiveBackend? =
        raw.takeIf(String::isNotEmpty)?.let { runCatching { LiveBackend.valueOf(it) }.getOrNull() }

    private fun parseEventNotificationLevel(raw: String): EventNotificationLevel =
        runCatching { EventNotificationLevel.valueOf(raw) }.getOrDefault(EventNotificationLevel.DEFAULT)

    private fun parseUpdateChannel(raw: String): UpdateChannel =
        runCatching { UpdateChannel.valueOf(raw) }.getOrDefault(UpdateChannel.STABLE)

    private fun parseUpdateSource(raw: String): UpdateSource =
        runCatching { UpdateSource.valueOf(raw) }.getOrDefault(UpdateSource.MIRRORCHYAN)
}
