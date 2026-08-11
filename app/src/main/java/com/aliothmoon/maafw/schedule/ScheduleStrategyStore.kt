package com.aliothmoon.maafw.schedule
import com.aliothmoon.maafw.MaaDispatchers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 定时规则的唯一读写入口，独立的 Preferences DataStore
 *
 * 不进 `UserConfiguration`：后者 schema 不符即整体重置，闹钟规则跟着被清会让用户莫名其妙地
 * 少了一堆定时；也不进 `AppSettings`：那是单值偏好，这里是有增删的列表
 *
 * [isLoaded] 给 receiver 用：BroadcastReceiver 拿不到挂起点，只能先等首值落地再读
 */
class ScheduleStrategyStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    val strategies: StateFlow<List<ScheduleStrategy>> = context.store.data
        .map { prefs ->
            decode(prefs[STRATEGIES_KEY]).also { _isLoaded.value = true }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun add(strategy: ScheduleStrategy) = mutate { it + strategy }

    suspend fun update(strategy: ScheduleStrategy) = mutate { current ->
        current.map { if (it.id == strategy.id) strategy else it }
    }

    suspend fun remove(strategyId: String) = mutate { current ->
        current.filterNot { it.id == strategyId }
    }

    suspend fun setEnabled(strategyId: String, enabled: Boolean) = mutate { current ->
        current.map { if (it.id == strategyId) it.copy(enabled = enabled) else it }
    }

    suspend fun recordTrigger(
        strategyId: String,
        result: TriggerResult,
        message: String? = null,
        triggeredAt: Long = System.currentTimeMillis(),
    ) = mutate { current ->
        current.map {
            if (it.id != strategyId) it
            else it.copy(lastTriggeredAt = triggeredAt, lastResult = result, lastResultMessage = message)
        }
    }

    fun findById(strategyId: String): ScheduleStrategy? = strategies.value.find { it.id == strategyId }

    private suspend fun mutate(transform: (List<ScheduleStrategy>) -> List<ScheduleStrategy>) {
        context.store.edit { prefs ->
            val next = transform(decode(prefs[STRATEGIES_KEY]))
            prefs[STRATEGIES_KEY] = json.encodeToString(next)
        }
    }

    /** 解析失败按空列表算：宁可用户重建规则，也不让开机广播因一条脏数据整批不注册 */
    private fun decode(raw: String?): List<ScheduleStrategy> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<ScheduleStrategy>>(raw) }
            .getOrElse {
                Timber.w(it, "Failed to parse schedule rules; treating as empty")
                emptyList()
            }
    }

    private companion object {
        val Context.store: DataStore<Preferences> by preferencesDataStore(name = "schedule_strategies")
        val STRATEGIES_KEY = stringPreferencesKey("strategies")
    }
}
