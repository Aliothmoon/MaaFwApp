package com.aliothmoon.maafw.schedule

import android.app.KeyguardManager
import android.content.Context
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
import kotlinx.coroutines.withTimeoutOrNull

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
    val wakeUnlockEnabled: Boolean,
    val wakeCredential: String,
)

private data class HealthState(
    val issues: List<ScheduleHealthIssue>,
    val wizard: List<ScheduleHealthIssue>,
    val backend: RemoteBackend,
)

class ScheduleViewModel(
    private val store: ScheduleStrategyStore,
    private val alarms: ScheduleAlarmManager,
    private val triggerLog: ScheduleTriggerLog,
    configurationStore: UserConfigurationStore,
    permissionGateway: PermissionGateway,
    appSettings: AppSettingsGateway,
    context: Context,
) : ViewModel() {

    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val exactAlarmAllowed = MutableStateFlow(alarms.canScheduleExact())
    private val deviceSecure = MutableStateFlow(readDeviceSecure())
    private val wizardRequested = MutableStateFlow(false)
    private val loadedLog = MutableStateFlow<List<TriggerLogEntry>>(emptyList())

    private val healthSettings: Flow<HealthSettings> = combine(
        appSettings.runMode,
        appSettings.screenSaverEnabled,
        appSettings.wakeUnlockEnabled,
        appSettings.wakeCredential,
    ) { runMode, screenSaver, wakeUnlock, credential ->
        HealthSettings(runMode == RunMode.BACKGROUND, screenSaver, wakeUnlock, credential)
    }

    private val health: Flow<HealthState> = combine(
        store.strategies,
        permissionGateway.state,
        permissionGateway.systemPermissions,
        combine(exactAlarmAllowed, deviceSecure, ::Pair),
        healthSettings,
    ) { strategies, access, system, (exact, secure), settings ->
        val snapshot = ScheduleHealthSnapshot(
            hasEnabledRule = strategies.any { it.enabled },
            backendGranted = access.isGranted(access.configuredBackend),
            batteryWhitelist = system.batteryWhitelist,
            exactAlarmAllowed = exact,
            notification = system.notification,
            overlayGranted = system.overlay,
            overlayNeeded = ScheduleHealthLogic.overlayNeeded(
                settings.backgroundMode,
                settings.screenSaverEnabled,
            ),
            wakeCredentialMissing = ScheduleHealthLogic.wakeCredentialMissing(
                settings.wakeUnlockEnabled,
                secure,
                settings.wakeCredential,
            ),
        )
        HealthState(
            issues = ScheduleHealthLogic.failingIssues(snapshot),
            wizard = ScheduleHealthLogic.wizardItems(snapshot),
            backend = access.configuredBackend,
        )
    }

    private val healthWithWizard: Flow<HealthState> = combine(health, wizardRequested) { state, requested ->
        if (requested) state else state.copy(wizard = emptyList())
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
        )
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
                // 规则变了旧闹钟就不作数了，先撤后立
                alarms.cancel(intent.strategy.id)
                if (intent.strategy.enabled) alarms.scheduleNext(intent.strategy)
            }

            is ScheduleIntent.Delete -> {
                alarms.cancel(intent.strategyId)
                store.remove(intent.strategyId)
            }

            is ScheduleIntent.SetEnabled -> {
                store.setEnabled(intent.strategyId, intent.enabled)
                alarms.cancel(intent.strategyId)
                if (intent.enabled) {
                    store.findById(intent.strategyId)?.let { alarms.scheduleNext(it) }
                }
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

            is ScheduleIntent.RequestSetupWizard -> viewModelScope.launch {
                // 保存走的是编辑页那个 VM 实例，这边的 strategies 可能还没刷到；不等它落地，
                // 「第一条启用的规则」会被当成没有，引导一项都算不出来
                withTimeoutOrNull(SAVED_RULE_WAIT_MS) {
                    store.strategies.first { list -> list.any { it.id == intent.strategyId && it.enabled } }
                }
                // 当下就没有要引导的就不挂标记：否则日后哪项权限掉了，弹窗会凭空冒出来
                if (health.first().wizard.isNotEmpty()) wizardRequested.value = true
            }

            ScheduleIntent.DismissSetupWizard -> wizardRequested.value = false
        }
    }

    private fun readDeviceSecure(): Boolean = keyguard?.isDeviceSecure == true

    private companion object {
        const val SAVED_RULE_WAIT_MS = 2_000L
    }
}
