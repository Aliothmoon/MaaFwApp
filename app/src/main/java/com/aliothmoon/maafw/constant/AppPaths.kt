package com.aliothmoon.maafw.constant

import android.content.Context
import java.io.File

/**
 * App 进程运行期路径单例
 *
 * [init] 在 Application.onCreate 一口气解析所有目录并定值；不保留 Context 引用
 *
 * 外部存储不可用时 init 抛出 → app 启动即失败：PI/run/日志全依赖
 * 外部私有目录，不可用就没法降级，崩在启动比 UI 进去后处处报错更直接
 *
 * 与 RemoteBootTrace / ServiceBootLogger 同属进程级全局，不进 Koin：路径解析早于 DI 就绪
 */
object AppPaths {
    lateinit var externalRoot: File
        private set

    lateinit var logDir: File
        private set

    lateinit var focusDir: File
        private set

    lateinit var debugDir: File
        private set

    fun init(context: Context) {
        val root = checkNotNull(context.getExternalFilesDir(null)) {
            "外部私有目录不可用（外部存储未挂载）"
        }
        externalRoot = root
        logDir = File(root, AppFiles.LOG_DIR)
        focusDir = File(logDir, AppFiles.FOCUS_DIR)
        debugDir = File(root, AppFiles.DEBUG_DIR)
    }
}
