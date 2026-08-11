package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.theme.ThemeStyle
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 侧看得见的那部分 app 设置；实现是 [AppSettingsManager]
 * 与 [com.aliothmoon.maafw.privileged.PermissionGateway] 同一路子：让 VM 测试能塞 fake，
 * 而不必把 DataStore 一起拖进来
 */
interface AppSettingsGateway {
    val runMode: StateFlow<RunMode>
    suspend fun setRunMode(mode: RunMode)

    val overlayControlMode: StateFlow<OverlayControlMode>
    suspend fun setOverlayControlMode(mode: OverlayControlMode)

    val screenSaverEnabled: StateFlow<Boolean>
    suspend fun setScreenSaverEnabled(enabled: Boolean)

    /** 全局的跑完关目标应用；规则级同名开关在 ScheduleStrategy 上，本项优先 */
    val closeAppAfterTask: StateFlow<Boolean>
    suspend fun setCloseAppAfterTask(enabled: Boolean)

    val touchPreviewEnabled: StateFlow<Boolean>
    suspend fun setTouchPreviewEnabled(enabled: Boolean)

    val resolutionPreference: StateFlow<ResolutionPreference>
    suspend fun setResolutionPreference(preference: ResolutionPreference)

    val debugMode: StateFlow<Boolean>
    suspend fun setDebugMode(enabled: Boolean)

    val themeStyle: StateFlow<ThemeStyle>
    suspend fun setThemeStyle(style: ThemeStyle)

    // ── 定时任务解锁；逐条规则的那几项在 ScheduleStrategy 上，不在这 ──

    val wakeUnlockEnabled: StateFlow<Boolean>
    suspend fun setWakeUnlockEnabled(enabled: Boolean)

    /** 纯数字 PIN；非数字会被 setter 过滤掉 */
    val wakeCredential: StateFlow<String>
    suspend fun setWakeCredential(credential: String)

}
