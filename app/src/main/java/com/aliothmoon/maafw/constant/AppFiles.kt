package com.aliothmoon.maafw.constant

/**
 * 外部私有目录（`getExternalFilesDir(null)`）下的固定子路径
 * 特权进程是 shell 身份，只有这里既写得进又和 app 看到同一份
 */
object AppFiles {
    /** 特权进程启动排查用的日志落点；起不来时这是唯一可观测面 */
    const val DEBUG_DIR = "debug"
}
