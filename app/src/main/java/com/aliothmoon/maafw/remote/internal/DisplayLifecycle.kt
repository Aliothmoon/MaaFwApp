package com.aliothmoon.maafw.remote.internal

/**
 * 虚拟屏与主屏采集共用 bridge_capture 里那一份 native capturer：起停、换分辨率、主屏尺寸变化重建都持这把锁，
 * 两条线程同时走到 `AImageReader_delete` 就是 double free，新建的屏也会被写进已撤的状态里漏掉
 *
 * 卡住的一个会让后面全排队，由 app 侧 `callOrRestart` 限时判卡、重启特权进程收场；`dumpThreads` 不拿它
 */
internal object DisplayLifecycle {
    val lock = Any()
}
