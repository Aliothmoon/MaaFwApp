package com.aliothmoon.maafw.constant

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * App 进程运行期路径单例
 *
 * [init] 在 Application.onCreate 一口气解析所有目录并定值；不保留 Context 引用
 *
 * 存储没就绪（闹钟冷启动撞上开机早期的 ENOENT / ENOTCONN）时 getExternalFilesDir 返回 null，
 * 退回它本该返回的推导路径，不崩在 onCreate——崩了闹钟链要断到下次进程启动。这期间 PI 读不到，
 * 落到 ProjectNotReady；存储就绪后同一路径照常可用
 *
 * 与 RemoteBootTrace / ServiceBootLogger 同属进程级全局，不进 Koin：路径解析早于 DI 就绪
 */
object AppPaths {
    lateinit var ROOT: File
        private set

    lateinit var LOG_DIR: File
        private set

    lateinit var FOCUS_DIR: File
        private set

    lateinit var DEBUG_DIR: File
        private set

    /** 更新 APK 下载缓存；挂在内部 cacheDir 下，不属于 [AppFiles] 的外部私有目录树 */
    lateinit var UPDATES_CACHE_DIR: File
        private set

    /** [ROOT] 落在推导路径上；init 早于日志树，由 Application 种好树后补记 */
    var rootFallback: Boolean = false
        private set

    fun init(context: Context) {
        val real = context.getExternalFilesDir(null)
        rootFallback = real == null
        val root = real ?: expectedExternalFilesDir(context.packageName)
        ROOT = root
        LOG_DIR = File(root, AppFiles.LOG_DIR)
        FOCUS_DIR = File(LOG_DIR, AppFiles.FOCUS_DIR)
        DEBUG_DIR = File(root, AppFiles.DEBUG_DIR)
        UPDATES_CACHE_DIR = File(context.cacheDir, "updates")
    }

    /** 只拼路径不碰存储，与 RemoteBootTrace 同一推导 */
    @Suppress("DEPRECATION")
    private fun expectedExternalFilesDir(packageName: String) =
        File(Environment.getExternalStorageDirectory(), "Android/data/$packageName/files")
}
