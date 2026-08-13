package com.aliothmoon.maafw.schedule

import androidx.lifecycle.ViewModel
import com.aliothmoon.maafw.config.UserConfigurationStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 定时规则的 Activity 作用域会话
 *
 * 与 [com.aliothmoon.maafw.session.SessionViewModel] 分开：定时不依赖 PI，也不参与运行锁定——
 * 混进去只会让那颗聚合态多一堆无关重组
 *
 * 读 [UserConfigurationStore] 只为列出「跑哪份配置」的候选（id + 名字），
 * 不解析 PI、不做 resolve
 */
/** 配置列表与当前激活项一起取；分两条流会让 combine 多一元且两者本就同源 */
private data class ConfigurationSnapshot(
    val options: List<ScheduleConfigurationOption>,
    val activeId: String?,
)

class ScheduleViewModel(
    private val store: ScheduleStrategyStore,
    private val alarms: ScheduleAlarmManager,
    private val triggerLog: ScheduleTriggerLog,
    configurationStore: UserConfigurationStore,
) : ViewModel() {

    private val exactAlarmAllowed = MutableStateFlow(alarms.canScheduleExact())
    private val loadedLog = MutableStateFlow<List<TriggerLogEntry>>(emptyList())

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
    ) { strategies, exact, log, configs ->
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

            ScheduleIntent.RefreshExactAlarmPermission ->
                exactAlarmAllowed.value = alarms.canScheduleExact()
        }
    }
}
