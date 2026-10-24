package com.aliothmoon.maafw.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前 UI 正在操作的实例号
 *
 * 真相在 [com.aliothmoon.maafw.privileged.RemoteServiceManager]，但配置层（config）反过来去依赖
 * 特权连接层会与 Koin 的单例图形成环，所以在这里落一个中性入口：那边切路时顺手同步过来，
 * 这边只读。0 是主实例。
 */
object ActiveSlotProvider {

    private val _slot = MutableStateFlow(0)

    val flow: StateFlow<Int> = _slot.asStateFlow()

    val current: Int get() = _slot.value

    fun set(slot: Int) {
        _slot.value = slot
    }
}
