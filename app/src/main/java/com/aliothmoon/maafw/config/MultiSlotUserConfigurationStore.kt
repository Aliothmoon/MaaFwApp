package com.aliothmoon.maafw.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.aliothmoon.maafw.constant.DataStoreFile
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 主实例：沿用 App 原有的那一份配置，也是其它实例播种的来源 */
private const val PRIMARY_SLOT = 0

/**
 * 每路各记一套配置
 *
 * 0 号实例仍旧读写原来的 `user_config.json`——用户既有那份配置原样留在它身上，不做迁移；
 * 其余实例各占一个 `user_config_<slot>.json`。
 *
 * 某个实例第一次被访问时，以 0 号实例**当时**的配置为起点复制一份，此后两路互不影响：
 * 切到实例二看到的就是实例一的配置副本，在实例二里改包名只落在实例二。
 *
 * 主题这类**看着是全局的外观项**只认 0 号实例——切个实例让界面忽明忽暗不是用户要的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiSlotUserConfigurationStore(
    private val context: Context,
    /** 0 号实例的存储：注入 App 本来那一个，同文件开两个 DataStore 会被框架拒绝 */
    private val primary: DataStore<UserConfiguration>,
    private val activeSlot: () -> Int = { ActiveSlotProvider.current },
    activeSlotFlow: Flow<Int> = ActiveSlotProvider.flow,
) : UserConfigurationStore {

    private val stores = ConcurrentHashMap<Int, DataStore<UserConfiguration>>()

    private fun fileName(slot: Int): String = "user_config_$slot"

    private fun fileOf(slot: Int): File = File(context.filesDir, "datastore/${fileName(slot)}")

    private fun store(slot: Int): DataStore<UserConfiguration> =
        if (slot == PRIMARY_SLOT) {
            primary
        } else {
            stores.computeIfAbsent(slot) {
                DataStoreFactory.create(
                    serializer = UserConfigurationSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler { UserConfiguration() },
                    produceFile = { fileOf(slot) },
                )
            }
        }

    /**
     * 取某一路的存储，必要时先播种
     *
     * 判「有没有配过」看文件在不在而不是看字段：`DataStoreFactory.create` 是惰性的，
     * 这里查文件时它还没被读写过，所以这个判断落在第一次读之前。
     */
    private suspend fun seeded(slot: Int): DataStore<UserConfiguration> {
        val target = store(slot)
        if (slot != PRIMARY_SLOT && !fileOf(slot).exists()) {
            val seed = store(PRIMARY_SLOT).data.first()
            target.updateData { seed }
        }
        return target
    }

    override val data: Flow<UserConfiguration> = activeSlotFlow
        .flatMapLatest { slot -> dataFor(slot) }
        .distinctUntilChanged()

    private fun dataFor(slot: Int): Flow<UserConfiguration> = flow {
        val target = seeded(slot)
        if (slot == PRIMARY_SLOT) {
            emitAll(target.data)
        } else {
            emitAll(
                combine(store(PRIMARY_SLOT).data, target.data) { global, mine ->
                    mine.copy(
                        themeMode = global.themeMode,
                        welcomeFingerprint = global.welcomeFingerprint,
                        initialized = global.initialized,
                    )
                },
            )
        }
    }

    override suspend fun update(transform: (UserConfiguration) -> UserConfiguration): UserConfiguration {
        val slot = activeSlot()
        if (slot == PRIMARY_SLOT) return store(slot).updateData(transform)

        val target = seeded(slot)
        val global = store(PRIMARY_SLOT).data.first()
        val current = target.data.first().copy(
            themeMode = global.themeMode,
            welcomeFingerprint = global.welcomeFingerprint,
            initialized = global.initialized,
        )
        val next = transform(current)
        // 外观项只在 0 号实例上改；子实例存自己的运行配置，那几个字段跟随 0 号
        if (next.themeMode != global.themeMode) {
            store(PRIMARY_SLOT).updateData { it.copy(themeMode = next.themeMode) }
        }
        return target.updateData {
            next.copy(
                themeMode = it.themeMode,
                welcomeFingerprint = it.welcomeFingerprint,
                initialized = it.initialized,
            )
        }
    }

    /**
     * 把某一路恢复成「刚建出来」的样子（实例被删除时调用）
     *
     * 这里重播种而不是删文件：DataStore 实例可能还被别的协程握着，动文件容易撞上
     * 「文件没了但句柄还在」的怪状态；写一次与 0 号实例当前内容相同的值，效果一样干净。
     * 本进程还没加载过的路不用管——下次访问时 [seeded] 会以 0 号为起点播种
     */
    override suspend fun forgetSlot(slot: Int) {
        if (slot == PRIMARY_SLOT) return
        val target = stores[slot] ?: return
        val seed = store(PRIMARY_SLOT).data.first()
        target.updateData { seed }
    }

    override fun dataForSlot(slot: Int): Flow<UserConfiguration> = dataFor(slot)

    override suspend fun updateSlot(slot: Int, transform: (UserConfiguration) -> UserConfiguration): UserConfiguration {
        if (slot == PRIMARY_SLOT) return store(slot).updateData(transform)
        val target = seeded(slot)
        val global = store(PRIMARY_SLOT).data.first()
        val current = target.data.first().copy(
            themeMode = global.themeMode,
            welcomeFingerprint = global.welcomeFingerprint,
            initialized = global.initialized,
        )
        val next = transform(current)
        if (next.themeMode != global.themeMode) {
            store(PRIMARY_SLOT).updateData { it.copy(themeMode = next.themeMode) }
        }
        return target.updateData {
            next.copy(
                themeMode = it.themeMode,
                welcomeFingerprint = it.welcomeFingerprint,
                initialized = it.initialized,
            )
        }
    }
}
