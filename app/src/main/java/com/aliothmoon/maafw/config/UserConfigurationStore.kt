package com.aliothmoon.maafw.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** DataStore 为唯一事实来源 */
interface UserConfigurationStore {
    val data: Flow<UserConfiguration>
    suspend fun update(transform: (UserConfiguration) -> UserConfiguration): UserConfiguration
}

class DataStoreUserConfigurationStore(
    private val dataStore: DataStore<UserConfiguration>,
    private val serializer: UserConfigurationSerializer,
) : UserConfigurationStore {

    /** 旧版明文文件只在首个订阅者读到后立刻重写为加密格式 */
    override val data: Flow<UserConfiguration> = flow {
        dataStore.data.collect { value ->
            emit(value)
            if (serializer.consumeLegacyRead()) {
                emit(dataStore.updateData { current -> current })
            }
        }
    }

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
class UserConfigurationSerializer(
    private val cipher: UserConfigurationCipher,
) : Serializer<UserConfiguration> {

    val schemaVersion = 1
    private val legacyRead = AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: UserConfiguration = UserConfiguration()

    override suspend fun readFrom(input: InputStream): UserConfiguration {
        val bytes = withContext(Dispatchers.IO) { input.readBytes() }
        val isLegacyPlaintext = bytes.firstOrNull { it != ' '.code.toByte() } == '{'.code.toByte()
        val text = if (isLegacyPlaintext) {
            legacyRead.set(true)
            bytes.decodeToString()
        } else {
            if (bytes.isEmpty()) return defaultValue
            try {
                cipher.decrypt(bytes).decodeToString()
            } catch (e: Exception) {
                throw CorruptionException("UserConfiguration 解密失败", e)
            }
        }
        if (text.isBlank()) return defaultValue
        val envelope = try {
            json.decodeFromString<PersistedUserConfiguration>(text)
        } catch (e: SerializationException) {
            throw CorruptionException("UserConfiguration 反序列化失败", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("UserConfiguration 结构非法", e)
        }
        if (envelope.schemaVersion != schemaVersion) return defaultValue
        return envelope.config
    }

    override suspend fun writeTo(t: UserConfiguration, output: OutputStream) {
        val text = json.encodeToString(PersistedUserConfiguration(schemaVersion, t))
        val encrypted = try {
            cipher.encrypt(text.encodeToByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("UserConfiguration 加密失败", e)
        }
        withContext(Dispatchers.IO) {
            output.write(encrypted)
        }
    }

    fun consumeLegacyRead(): Boolean = legacyRead.getAndSet(false)
}

interface UserConfigurationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}
