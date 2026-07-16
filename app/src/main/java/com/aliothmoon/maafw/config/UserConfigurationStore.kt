package com.aliothmoon.maafw.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** UserConfiguration 的读写 seam；DataStore 是唯一事实来源。 */
interface UserConfigurationStore {
    val data: Flow<UserConfiguration>
    suspend fun update(transform: (UserConfiguration) -> UserConfiguration): UserConfiguration
}

class DataStoreUserConfigurationStore(
    private val dataStore: DataStore<UserConfiguration>,
) : UserConfigurationStore {

    override val data: Flow<UserConfiguration> = dataStore.data

    override suspend fun update(transform: (UserConfiguration) -> UserConfiguration): UserConfiguration =
        dataStore.updateData(transform)
}

/** schemaVersion 信封属于序列化层内部实现，不出现在领域名称中。 */
@Serializable
private data class PersistedUserConfiguration(
    val schemaVersion: Int,
    val config: UserConfiguration,
)

/**
 * 版本策略见 docs/persistence-diagnostics.md §9：
 * schemaVersion 不受支持时不做猜测式转换，重置为未初始化状态重走首次初始化；
 * 信封级损坏数据抛 CorruptionException，由 ReplaceFileCorruptionHandler 兜底。
 */
object UserConfigurationSerializer : Serializer<UserConfiguration> {

    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: UserConfiguration = UserConfiguration()

    override suspend fun readFrom(input: InputStream): UserConfiguration {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        val envelope = try {
            json.decodeFromString<PersistedUserConfiguration>(text)
        } catch (e: SerializationException) {
            throw CorruptionException("UserConfiguration 反序列化失败", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("UserConfiguration 结构非法", e)
        }
        if (envelope.schemaVersion != SCHEMA_VERSION) return defaultValue
        return envelope.config
    }

    override suspend fun writeTo(t: UserConfiguration, output: OutputStream) {
        val text = json.encodeToString(PersistedUserConfiguration(SCHEMA_VERSION, t))
        output.write(text.encodeToByteArray())
    }
}
