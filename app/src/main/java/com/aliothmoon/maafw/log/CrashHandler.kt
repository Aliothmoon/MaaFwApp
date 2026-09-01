package com.aliothmoon.maafw.log

import android.os.Build
import android.util.Log
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.DeviceInfoText.formatGitValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落盘
 *
 * logcat 的环形缓冲装不下从崩溃到用户来反馈之间那段时间，而崩溃现场恰恰只有一次机会。
 * 落在 `log/crash/`，跟着导出包一起交出去
 *
 */
class CrashHandler : Thread.UncaughtExceptionHandler {
    private val logDir = File(AppPaths.LOG_DIR, AppFiles.CRASH_DIR)
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        pruneOld()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 这里已经在崩溃路径上，任何一步再抛都会把现场彻底吃掉
        runCatching { write(report(thread, throwable)) }
        previous?.uncaughtException(thread, throwable)
    }

    private fun report(thread: Thread, throwable: Throwable): String = buildString {
        append("Time     : ").append(STAMP_READABLE.format(Date())).append('\n')
        append("Thread   : ").append(thread.name).append('\n')
        append("Version  : ").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(") ")
        append(BuildConfig.BUILD_TYPE).append('\n')
        append("Git      : ")
            .append(formatGitValue(BuildConfig.MAFW_GIT_TAG, BuildConfig.MAFW_GIT_COMMIT))
            .append('\n')
        append("Parent   : ").append(
            formatGitValue(
                BuildConfig.MAFW_PARENT_GIT_TAG,
                BuildConfig.MAFW_PARENT_GIT_COMMIT,
                notSubmodule = BuildConfig.MAFW_PARENT_GIT_COMMIT.isEmpty(),
            ),
        ).append('\n')
        append("Device   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append('\n')
        append("System   : Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABI      : ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append('\n')
        append(Log.getStackTraceString(throwable))
    }

    private fun write(content: String) {
        val dir = logDir.apply { mkdirs() }
        File(dir, "crash_${STAMP_FILE.format(Date())}.txt").writeText(content)
    }

    private fun pruneOld() {
        runCatching {
            logDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val MAX_FILES = 10

        val STAMP_FILE = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val STAMP_READABLE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
