package com.aliothmoon.maafw.constant

import android.content.Context
import java.io.File

/**
 * 外部私有目录（getExternalFilesDir(null)）下的固定子路径
 * 特权进程是 shell 身份，只有这里既写得进又和 app 看到同一份
 *
 * 三棵子树：[LOG_DIR] 运行日志、[DEBUG_DIR] 启动诊断、[PI_DIR] PI 解包内容
 */
object AppFiles {
    /** 运行日志树：Timber、native MaaFramework、定时触发记录、缓存帧 */
    const val LOG_DIR = "log"

    /** 启动诊断树：服务绑定/启动 trace、root launcher 输出、logcat 抓取 */
    const val DEBUG_DIR = "debug"

    /** PI 解包树：native MaaFramework 只认文件系统路径，从 assets 解到这里 */
    const val PI_DIR = "pi"

    /** [LOG_DIR] 下：缓存帧轮转目录 */
    const val FOCUS_DIR = "focus"

    /** [DEBUG_DIR] 下：logcat 抓取子目录（core=特权进程，app=App 进程） */
    const val LOGCAT_CORE_DIR = "logcat/core"
    const val LOGCAT_APP_DIR = "logcat/app"
}

