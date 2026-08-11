package com.aliothmoon.maafw.session

import android.view.Surface
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ResolvedEnvironment
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.WatchdogState
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.privileged.ShizukuReadiness
import com.aliothmoon.maafw.privileged.SystemPermission
import com.aliothmoon.maafw.privileged.SystemPermissionState
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy

/** 跨页工作会话聚合态；Activity 作用域单实例，UI 只读 */
data class SessionUiState(
    val projectState: ProjectState = ProjectState.Loading,
    val configurationList: List<ResolvedRunConfiguration> = emptyList(),
    val activeConfiguration: ResolvedRunConfiguration? = null,
    val taskCatalog: List<TaskCatalogGroup> = emptyList(),
    val environment: ResolvedEnvironment? = null,
    val sessionDiagnostics: List<Diagnostic> = emptyList(),
    val runner: RunnerState = RunnerState(),
    val themeMode: ThemeMode = ThemeMode.System,
    val themeStyle: ThemeStyle = ThemeStyle.DEFAULT,
    val debugMode: Boolean = false,
    val runMode: RunMode = RunMode.BACKGROUND,
    val overlayControlMode: OverlayControlMode = OverlayControlMode.FLOAT_BALL,
    val screenSaverEnabled: Boolean = false,
    /** 定时任务的亮屏解锁；逐条规则的收尾选项在 ScheduleStrategy 上，不在这 */
    val wakeUnlockEnabled: Boolean = false,
    val wakeCredential: String = "",
    val resolutionPreference: ResolutionPreference = ResolutionPreference.P720,
    /**
     * 预览画面的尺寸：后台模式是虚拟屏尺寸（PI controller 的 display_* 推导），
     * 前台模式即设备屏幕尺寸。项目未就绪时为 null
     */
    val previewResolution: DisplayResolution? = null,
    /** 目标 app 在虚拟屏上的看门狗状态；预览小窗右上角徽标用它（AppWatchdog） */
    val watchdogState: WatchdogState = WatchdogState.IDLE,
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
    /** 授权请求进行中；只压按钮，不进 configurationLocked */
    val remoteAccessGranting: Boolean = false,
    val shizukuReadiness: ShizukuReadiness = ShizukuReadiness(),
    val privilegedService: PrivilegedServiceState = PrivilegedServiceState.Disconnected,
    val systemPermissions: SystemPermissionState = SystemPermissionState(),
) {
    val remoteAccessGranted: Boolean
        get() = remoteAccess.isGranted(remoteAccess.configuredBackend)

    val privilegedServiceConnected: Boolean
        get() = privilegedService == PrivilegedServiceState.Connected

    /**
     * 首页那一行服务状态：连接态、项目加载、运行态三路归约成一句
     *
     * 判定顺序即优先级，不可换：连不上特权进程时，项目加载与运行态的信息都没有意义
     */
    val serviceStatus: ServiceStatus
        get() = when {
            privilegedService == PrivilegedServiceState.Connecting ->
                ServiceStatus(uiTextOf(R.string.home_status_connecting), StatusTone.Warning, busy = true)

            // 崩过一次和从没连过要分开报：前者用户得知道「刚才断了」，后者只是还没开始
            privilegedService == PrivilegedServiceState.Died ->
                ServiceStatus(uiTextOf(R.string.home_status_died), StatusTone.Error)

            privilegedService == PrivilegedServiceState.Error ->
                ServiceStatus(uiTextOf(R.string.home_status_error), StatusTone.Error)

            privilegedService == PrivilegedServiceState.Disconnected ->
                ServiceStatus(uiTextOf(R.string.home_status_disconnected), StatusTone.Neutral)

            projectState is ProjectState.Loading ->
                ServiceStatus(uiTextOf(R.string.home_status_project_loading), StatusTone.Warning, busy = true)

            projectState is ProjectState.Error ->
                ServiceStatus(uiTextOf(R.string.home_status_project_failed), StatusTone.Error)

            runner.phase is RunnerPhase.Unavailable ->
                ServiceStatus(uiTextOf(R.string.home_status_runner_unavailable), StatusTone.Error)

            runner.phase == RunnerPhase.Preparing ->
                ServiceStatus(uiTextOf(R.string.home_status_preparing), StatusTone.Warning, busy = true)

            runner.phase == RunnerPhase.Running ->
                ServiceStatus(uiTextOf(R.string.home_status_running), StatusTone.Primary, busy = true)

            runner.phase == RunnerPhase.Stopping ->
                ServiceStatus(uiTextOf(R.string.home_status_stopping), StatusTone.Warning, busy = true)

            else -> ServiceStatus(uiTextOf(R.string.home_status_ready), StatusTone.Primary)
        }

    /** 唯一锁定规则：RunnerPhase.isBusy */
    val configurationLocked: Boolean
        get() = runner.phase.isBusy

    /** 项目加载诊断 + 会话解析诊断合流 */
    val visibleDiagnostics: List<Diagnostic>
        get() = (projectState as? ProjectState.Ready)?.diagnostics.orEmpty() + sessionDiagnostics

    val canStart: Boolean
        get() = runner.phase == RunnerPhase.Idle && activeConfiguration != null
}

/**
 * 状态的语气，不是颜色
 *
 * UiState 里放具体 Color 会把 colorScheme 绑死在 ViewModel 上，深色主题下就换不掉了；
 * 映射到实际颜色是 UI 层的事
 */
enum class StatusTone { Neutral, Primary, Warning, Error }

/** [SessionUiState.serviceStatus] 的三件套：说什么、什么语气、要不要转圈 */
data class ServiceStatus(
    val text: UiText,
    val tone: StatusTone,
    val busy: Boolean = false,
)

enum class PreviewTouchAction { Down, Move, Up }

sealed interface SessionIntent {
    data class CreateConfiguration(val name: String) : SessionIntent

    /**
     * configurationName 空白/null 沿用模板名
     * taskNames null = 模板全部任务，非 null 按模板声明序过滤
     */
    data class CreateFromTemplate(
        val templateName: String,
        val configurationName: String? = null,
        val taskNames: List<String>? = null,
    ) : SessionIntent
    data class SelectConfiguration(val id: RunConfigurationId) : SessionIntent
    data class DuplicateConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class RenameConfiguration(val id: RunConfigurationId, val name: String) : SessionIntent
    data class DeleteConfiguration(val id: RunConfigurationId) : SessionIntent

    /** 每个名称追加独立 ConfiguredTask 实例（可重复 taskName） */
    data class ConfirmAddTasks(
        val configurationId: RunConfigurationId,
        val orderedTaskNames: List<String>,
    ) : SessionIntent

    data class RemoveTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
    ) : SessionIntent

    /** 克隆任务（新 instanceId，继承 taskName/启用/选项）；customLabel 由调用方算好「展示名 (副本)」传入 */
    data class DuplicateTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val customLabel: String,
    ) : SessionIntent

    /** 改显示别名；null/空白 = 清除别名回退定义 label（规范 taskName 不动，pipeline 不受影响） */
    data class RenameTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val customLabel: String?,
    ) : SessionIntent

    data class ToggleTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val enabled: Boolean,
    ) : SessionIntent

    /** targetIndex：移除原位置后的插入下标 */
    data class MoveTask(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val targetIndex: Int,
    ) : SessionIntent

    data class SetTaskOption(
        val configurationId: RunConfigurationId,
        val taskInstanceId: String,
        val optionName: String,
        val value: OptionValue,
    ) : SessionIntent

    data class SelectResource(val resourceName: String) : SessionIntent
    data class SetThemeMode(val mode: ThemeMode) : SessionIntent
    data class SetThemeStyle(val style: ThemeStyle) : SessionIntent

    /**
     * null = 跟随系统；事实来源 AppLocales
     * 落地：Activity 重建 → AppRoot 检测 locale → ReloadProject
     */
    data class SetLanguage(val localeTag: String?) : SessionIntent
    data class SetDebugMode(val enabled: Boolean) : SessionIntent

    /** 主屏 / 后台虚拟屏；运行中不允许改，下一轮才生效 */
    data class SetRunMode(val mode: RunMode) : SessionIntent

    data class SetOverlayControlMode(val mode: OverlayControlMode) : SessionIntent

    /** 仅后台模式：运行期是否自动盖屏保 */
    data class SetScreenSaverEnabled(val enabled: Boolean) : SessionIntent

    data class SetWakeUnlockEnabled(val enabled: Boolean) : SessionIntent

    /** 非数字会被落盘那一层滤掉：注入按键只打得出 0-9 */
    data class SetWakeCredential(val credential: String) : SessionIntent


    /** 虚拟屏分辨率偏好：720P / 1080P */
    data class SetResolutionPreference(val preference: ResolutionPreference) : SessionIntent

    /** 开启前台模式的控制层；要 Application 上下文挂窗口，转成 Effect */
    data object ShowOverlay : SessionIntent

    /** 不等运行开始，立刻盖上屏保；同样要 Application 上下文 */
    data object ShowScreenSaver : SessionIntent
    data object ReloadProject : SessionIntent

    data object Start : SessionIntent
    data object Stop : SessionIntent

    /**
     * 预览 Surface 的生死；Surface 归 UI 所有，VM 只转发句柄
     * 尺寸对不上虚拟屏时不要发：特权进程按 Surface 尺寸贴图，对不上就是拉伸的画面
     */
    data class AttachPreviewSurface(val surface: Surface) : SessionIntent
    data object DetachPreviewSurface : SessionIntent

    /**
     * 用户在全屏预览上的手动操作；坐标由 UI 换算到虚拟屏坐标系后传入
     * 高频（一次滑动几十条），走 oneway，不产生 Effect 也不改 UiState
     */
    data class PreviewTouch(val x: Int, val y: Int, val action: PreviewTouchAction) : SessionIntent

    /** 向当前后端发起授权；不走 guarded，运行中也允许（授权不改配置） */
    data object RequestRemoteAccess : SessionIntent

    /**
     * 手动开关特权进程；连着就断，没连就连（缺授权时顺带发起授权）
     * 同样不走 guarded——特权进程崩在运行途中时，这恰恰是唯一的自救入口
     */
    data object TogglePrivilegedService : SessionIntent

    /** 引导弹窗上的「跳过检查」；只压引导，不影响授权判定 */
    data object SkipShizukuCheck : SessionIntent

    /** 两者都要 Context 拉起外部 Activity，VM 转成 Effect 交给 UI */
    data object InstallShizuku : SessionIntent
    data object OpenShizuku : SessionIntent

    /** 系统权限页要 Activity 做宿主，同样转成 Effect */
    data class RequestSystemPermission(val permission: SystemPermission) : SessionIntent

    /** 从系统权限页回来后重读；这两项没有变更回调 */
    data object RefreshPermissions : SessionIntent

    data object ClearRunLog : SessionIntent
}

/** 一次性 Effect，不进 UiState */
sealed interface SessionEffect {
    data class ShowMessage(val message: UiText) : SessionEffect
    data class ShowDiagnostics(val diagnostics: List<Diagnostic>) : SessionEffect

    /** 拉起外部 Activity 需要 Context，由 Route 层执行 */
    data object InstallShizuku : SessionEffect
    data object OpenShizuku : SessionEffect
    data class RequestSystemPermission(val permission: SystemPermission) : SessionEffect

    data object ShowOverlay : SessionEffect
    data object ShowScreenSaver : SessionEffect

    /** 调试模式已启用并落盘，重启 App 让日志管线以新状态起来（对齐 MaaMeow） */
    data object RestartApp : SessionEffect
}
