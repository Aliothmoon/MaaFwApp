package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 内存版 UserConfigurationStore，供 JVM 单测与 Stub 场景使用。 */
class InMemoryUserConfigurationStore(
    initial: UserConfiguration = UserConfiguration(),
) : UserConfigurationStore {

    private val mutex = Mutex()
    private val _data = MutableStateFlow(initial)
    override val data: Flow<UserConfiguration> = _data.asStateFlow()

    val current: UserConfiguration get() = _data.value

    override suspend fun update(transform: (UserConfiguration) -> UserConfiguration): UserConfiguration =
        mutex.withLock {
            _data.updateAndGet(transform)
        }
}
