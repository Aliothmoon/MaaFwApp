package com.aliothmoon.maafw.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** DataStore 为唯一事实来源 */
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

/** schemaVersion 信封仅序列化层使用，不进领域名 */
@Serializable
private data class PersistedUserConfiguration(
    val schemaVersion: Int,
    val config: UserConfiguration,
)

/**
 * 见 docs/persistence-diagnostics.md §9
 * 不支持的 schemaVersion 不猜迁移，重置未初始化
 * 信封损坏抛 CorruptionException，由 ReplaceFileCorruptionHandler 兜底
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
        withContext(Dispatchers.IO) {
            output.write(text.encodeToByteArray())
        }
    }
}
