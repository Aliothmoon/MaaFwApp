package com.aliothmoon.maafw.schedule

import android.app.KeyguardManager
import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import androidx.lifecycle.ViewModel
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.settings.AppSettingsGateway
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定时规则的 Activity 作用域会话
 *
 * 与 [com.aliothmoon.maafw.session.SessionViewModel] 分开：定时不依赖 PI，也不参与运行锁定——
 * 混进去只会让那颗聚合态多一堆无关重组
 *
 * 读 [UserConfigurationStore] 只为列出「跑哪份配置」的候选（id + 名字），
 * 不解析 PI、不做 resolve
 *
 * 读 [PermissionGateway] 与 [AppSettingsGateway] 只为调度环境检查（[ScheduleHealthLogic]），
 * 授权动作仍由 SessionViewModel 那条路发起
 */
/** 配置列表与当前激活项一起取；分两条流会让 combine 多一元且两者本就同源 */
private data class ConfigurationSnapshot(
    val options: List<ScheduleConfigurationOption>,
    val activeId: String?,
)

/** 健康检查要看的那几项设置，先合成一份再进总的 combine */
private data class HealthSettings(
    val backgroundMode: Boolean,
    val screenSaverEnabled: Boolean,
    val anyRuleUsesScreenSaver: Boolean,
    val wakeUnlockType: String,
    val wakeCredential: String,
    val hasGesture: Boolean,
)

private data class HealthState(
    val issues: List<ScheduleHealthIssue>,
    val wizard: List<ScheduleHealthIssue>,
    val backend: RemoteBackend,
    val autoStart: AutoStartTarget? = null,
)

class ScheduleViewModel(
    private val store: ScheduleStrategyStore,
    private val alarms: ScheduleAlarmManager,
    private val triggerLog: ScheduleTriggerLog,
    configurationStore: UserConfigurationStore,
    permissionGateway: PermissionGateway,
    appSettings: AppSettingsGateway,
    gestureStore: UnlockGestureStore,
    context: Context,
) : ViewModel() {

    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val exactAlarmAllowed = MutableStateFlow(alarms.canScheduleExact())
    private val deviceSecure = MutableStateFlow(readDeviceSecure())
    private val wizardRequested = MutableStateFlow(false)
    private val autoStartPrompt = MutableStateFlow<AutoStartTarget?>(null)
    private val autoStartPrefs = AutoStartHelper.prefs(context)
    private val appContext = context.applicationContext
    private val loadedLog = MutableStateFlow<List<TriggerLogEntry>>(emptyList())

    private val healthSettings: Flow<HealthSettings> = combine(
        appSettings.runMode,
        combine(appSettings.screenSaverEnabled, store.strategies) { saver, rules ->
            // 一条都没启用时按要用屏保算：健康卡在建第一条规则之前就该把缺的报出来（见 ScheduleHealth.failingIssues）
            val enabled = rules.filter { it.enabled }
            saver to (enabled.isEmpty() || enabled.any { !it.hardwareScreenOff })
        },
        appSettings.wakeUnlockType,
        appSettings.wakeCredential,
        gestureStore.gesture,
    ) { runMode, (screenSaver, anyRuleUsesSaver), unlockType, credential, gesture ->
        HealthSettings(runMode == RunMode.BACKGROUND, screenSaver, anyRuleUsesSaver, unlockType, credential, gesture != null)
    }

    private val health: Flow<HealthState> = combine(
        permissionGateway.state,
        permissionGateway.systemPermissions,
        combine(exactAlarmAllowed, deviceSecure, ::Pair),
        healthSettings,
    ) { access, system, (exact, secure), settings ->
        val snapshot = ScheduleHealthSnapshot(
            backendGranted = access.isGranted(access.configuredBackend),
            backendAvailable = access.isAvailable(access.configuredBackend),
            batteryWhitelist = system.batteryWhitelist,
            exactAlarmAllowed = exact,
            notification = system.notification,
            overlayGranted = system.overlay,
            overlayNeeded = ScheduleHealthLogic.overlayNeeded(
                settings.backgroundMode,
                settings.screenSaverEnabled,
                settings.anyRuleUsesScreenSaver,
            ),
            wakeCredentialMissing = ScheduleHealthLogic.wakeCredentialMissing(
                unlockType = settings.wakeUnlockType,
                deviceSecure = secure,
                pin = settings.wakeCredential,
                hasGesture = settings.hasGesture,
            ),
        )
        HealthState(
            issues = ScheduleHealthLogic.failingIssues(snapshot),
            wizard = ScheduleHealthLogic.wizardItems(snapshot),
            backend = access.configuredBackend,
        )
    }

    private val healthWithWizard: Flow<HealthState> = combine(
        health,
        wizardRequested,
        autoStartPrompt,
    ) { state, requested, autoStart ->
        val wizard = if (requested) state.wizard else emptyList()
        // 排在权限引导之后：先把能检测的补齐，最后才是这项查不到状态的
        state.copy(wizard = wizard, autoStart = autoStart.takeIf { wizard.isEmpty() })
    }

    private val configurations: Flow<ConfigurationSnapshot> = configurationStore.data
        .map { config ->
            ConfigurationSnapshot(
                options = config.configurations.map { ScheduleConfigurationOption(it.id.value, it.name) },
                activeId = config.activeConfigurationId?.value,
            )
        }
        .distinctUntilChanged()

    val uiState: StateFlow<ScheduleUiState> = combine(
        store.strategies,
        exactAlarmAllowed,
        loadedLog,
        configurations,
        healthWithWizard,
    ) { strategies, exact, log, configs, health ->
        ScheduleUiState(
            rows = strategies.map { strategy ->
                val missing = configs.options.none { it.id == strategy.runConfigurationId }
                ScheduleRow(
                    strategy = strategy,
                    nextTriggerAt = if (!strategy.enabled || missing) {
                        null
                    } else {
                        alarms.computeNextTrigger(strategy)?.toInstant()?.toEpochMilli()
                    },
                    configurationMissing = missing,
                )
            },
            configurations = configs.options,
            activeConfigurationId = configs.activeId,
            exactAlarmAllowed = exact,
            exactAlarmConfigurable = alarms.hasExactAlarmToggle(),
            triggerLog = log,
            healthIssues = health.issues,
            backend = health.backend,
            setupWizard = health.wizard,
            autoStartPrompt = health.autoStart,
        )
    }.combine(
        combine(appSettings.runMode, appSettings.closeAppAfterTask, ::Pair),
    ) { state, (runMode, globalCloseApp) ->
        state.copy(runMode = runMode, globalCloseAppAfterTask = globalCloseApp)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScheduleUiState(),
    )

    private val effectChannel = Channel<ScheduleEffect>(Channel.BUFFERED)
    val effects: Flow<ScheduleEffect> = effectChannel.receiveAsFlow()

    // 与 SessionViewModel 同样串行消费：写盘与重排闹钟不能交错
    private val intents = Channel<ScheduleIntent>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (intent in intents) handle(intent)
        }
    }

    fun onIntent(intent: ScheduleIntent) {
        intents.trySend(intent)
    }

    private suspend fun handle(intent: ScheduleIntent) {
        when (intent) {
            is ScheduleIntent.Save -> {
                // 兜底：UI 已按 isValid 门禁保存钮，这里再挡一道，不让残规则落盘
                if (!intent.strategy.isValid) return
                val existing = store.findById(intent.strategy.id)
                if (existing == null) store.add(intent.strategy) else store.update(intent.strategy)
                // 规则变了旧闹钟就不作数了，先撤后立；挂着的重试留着
                alarms.replace(intent.strategy)
            }

            is ScheduleIntent.Delete -> {
                alarms.cancel(intent.strategyId)
                store.remove(intent.strategyId)
            }

            is ScheduleIntent.SetEnabled -> {
                val written = store.setEnabled(intent.strategyId, intent.enabled)
                alarms.cancel(intent.strategyId)
                if (written?.enabled == true) alarms.scheduleNext(written)
            }

            ScheduleIntent.LoadTriggerLog -> loadedLog.value = triggerLog.readAll()
            is ScheduleIntent.DeleteTriggerLogEntry -> {
                triggerLog.delete(intent.stableId)
                loadedLog.value = triggerLog.readAll()
            }
            ScheduleIntent.ClearTriggerLog -> {
                triggerLog.clear()
                loadedLog.value = emptyList()
            }

            ScheduleIntent.RequestExactAlarmPermission ->
                effectChannel.send(ScheduleEffect.RequestExactAlarmPermission)

            ScheduleIntent.RefreshEnvironment -> {
                exactAlarmAllowed.value = alarms.canScheduleExact()
                deviceSecure.value = readDeviceSecure()
            }

            ScheduleIntent.RequestSetupWizard -> viewModelScope.launch {
                // 当下就没有要引导的就不挂标记：否则日后哪项权限掉了，弹窗会凭空冒出来
                if (health.first().wizard.isNotEmpty()) wizardRequested.value = true
                // 自启动查不到开没开，只能在刚配好规则这个时机问一句；用户说过不再提醒就不问
                autoStartPrompt.value = withContext(MaaDispatchers.IO) {
                    if (AutoStartHelper.isNeverRemind(autoStartPrefs)) null
                    else AutoStartHelper.resolveTarget(appContext)
                }
            }

            ScheduleIntent.DismissSetupWizard -> wizardRequested.value = false

            is ScheduleIntent.DismissAutoStartPrompt -> {
                autoStartPrompt.value = null
                if (intent.neverRemind) {
                    withContext(MaaDispatchers.IO) { AutoStartHelper.markNeverRemind(autoStartPrefs) }
                }
            }
        }
    }

    private fun readDeviceSecure(): Boolean = keyguard?.isDeviceSecure == true
}
