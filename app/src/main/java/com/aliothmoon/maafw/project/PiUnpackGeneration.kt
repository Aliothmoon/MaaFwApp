package com.aliothmoon.maafw.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 解包代数：每次解包落定（[PiInstallState.Ready]）加一
 *
 * 进程级唯一，与 `AppPaths` 同类。UI 里按路径读 PI 文件的缓存（`MaaPiIcon`）靠它作废「上回没读到」的记忆：
 * 「重新解压」先删整棵目录再解，期间已在组合里的页面请求的图标会被记成缺失，不作废就一直空白到进程重启
 */
object PiUnpackGeneration {
    private val _value = MutableStateFlow(0)
    val value: StateFlow<Int> = _value.asStateFlow()

    internal fun bump() = _value.update { it + 1 }
}
