package com.aliothmoon.maafw.notification

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * [NotificationSettings] 的唯一读写入口
 *
 * 与 [com.aliothmoon.maafw.settings.AppSettingsManager] 不同，这里**不做阻塞首读**：
 * 那边有 `() -> RemoteBackend` 这类拿不到挂起点的同步调用方，而推送的读取全在协程里
 * （provider 的 send、NotificationCenter 的收尾、ViewModel 的 stateIn），
 * 不必为它在启动路径上再压一次 DataStore 的同步读
 */
class NotificationSettingsManager(private val context: Context) {

    companion object {
        private val Context.notificationDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "notification_settings")
    }

    val settings: Flow<NotificationSettings> =
        with(NotificationSettingsSchema) { context.notificationDataStore.flow }

    suspend fun current(): NotificationSettings = settings.first()

    suspend fun update(new: NotificationSettings) =
        with(NotificationSettingsSchema) { context.notificationDataStore.update(new) }
}

/** 逗号分隔的已启用渠道 id；空串要滤掉，否则会多出一个 id 为 "" 的渠道 */
fun NotificationSettings.enabledProviderIds(): List<String> =
    enabledProviders.split(",").filter(String::isNotBlank)

/** 盘上是历史遗留或手改的非法值时按 false 处理，不让读取本身抛异常 */
fun String.toPrefBoolean(default: Boolean = false): Boolean =
    toBooleanStrictOrNull() ?: default
