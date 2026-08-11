package com.aliothmoon.maafw

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 后台协程调度器的统一入口
 *
 * 组件内部直接引用，不为单测把 dispatcher 塞进构造参数；单测 mockkObject(MaaDispatchers)
 * 替换为 TestDispatcher 驱动 testScheduler 控制协程时序
 *
 * Main 不收进来：UI/前台 scope 走 Dispatchers.Main + setMain，那是另一套可测手段
 */
object MaaDispatchers {
    val IO: CoroutineDispatcher get() = Dispatchers.IO
    val Default: CoroutineDispatcher get() = Dispatchers.Default
}
