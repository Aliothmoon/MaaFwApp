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

    /**
     * 丢掉某一路自己的那份配置；实例被删除时调用
     *
     * 号位会被下一个新实例复用，不清掉的话新实例一上手就带着上一任的任务勾选与参数。
     * 单路实现没有「别的路」，默认什么都不做
     */
    suspend fun forgetSlot(slot: Int) = Unit

    /**
     * 读取指定实例的配置流。
     * 单实例实现忽略 slot 参数直接返回 [data]；
     * 多实例实现返回对应 slot 的配置（首次访问会从主实例播种）。
     * 用于定时功能跨实例查找/同步运行配置。
     */
    fun dataForSlot(slot: Int): Flow<UserConfiguration> = data

    /**
     * 更新指定实例的配置。
     * 单实例实现忽略 slot 参数等同于 [update]；
     * 多实例实现直接写入对应 slot 的存储，不依赖当前活动实例。
     * 用于定时功能触发前把配置同步到目标实例。
     */
    suspend fun updateSlot(slot: Int, transform: (UserConfiguration) -> UserConfiguration): UserConfiguration =
        update(transform)
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
        return envelope.config.withSecretsOpened(OptionSecretCipher::open)
    }

    /**
     * password 字段只在这一层加解密：内存里的领域值恒为明文，磁盘上恒为密文。
     * 新增的 secretFields / sealed 都有缺省值，旧文件照常读，所以 [SCHEMA_VERSION] 不动
     */
    override suspend fun writeTo(t: UserConfiguration, output: OutputStream) {
        val text = json.encodeToString(PersistedUserConfiguration(SCHEMA_VERSION, t.withSecretsSealed(OptionSecretCipher::seal)))
        withContext(Dispatchers.IO) {
            output.write(text.encodeToByteArray())
        }
    }
}
