package com.aliothmoon.maafw.log

import android.os.Build
import android.util.Log
import com.aliothmoon.maafw.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * app 侧日志的落盘出口
 *
 * logcat 的环形缓冲装不下一次长跑，出了问题让用户复现再抓也不现实——尤其定时触发那条链
 * 多半发生在半夜。落在外部私有目录，与 MaaFramework 的 maa.log、定时触发日志同一个 `log/`，
 * 一次 `adb pull` 全带走
 *
 * 写入串行且异步：调用点可能在任意线程甚至主线程的热路径上，不能让它等 IO。
 * 通道满了就丢——日志不该反压业务
 */
class AppLogWriter(private val logDir: () -> File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val channel = Channel<String>(capacity = QUEUE_CAPACITY)

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var stream: BufferedOutputStream? = null

    /** -1 表示还没打开过文件，下次写入时从磁盘读实际大小 */
    private var writtenBytes: Long = -1L

    init {
        scope.launch {
            writeHeader()
            for (entry in channel) append(entry)
        }
    }

    fun submit(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        // trySend 不阻塞；满了直接丢，宁可少几行日志也不能拖住调用方
        channel.trySend(format(priority, tag, message, throwable))
    }

    /** 每次启动打一段环境信息：排障时最先要问的就是机型与版本 */
    private fun writeHeader() {
        append(
            buildString {
                append("\n").append("=".repeat(60)).append("\n")
                append("启动时间 : ").append(ZonedDateTime.now().format(timestampFormat)).append("\n")
                append("版本     : ").append(BuildConfig.VERSION_NAME)
                append(" (").append(BuildConfig.VERSION_CODE).append(") ")
                append(BuildConfig.BUILD_TYPE).append("\n")
                append("设备     : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
                append("系统     : Android ").append(Build.VERSION.RELEASE)
                append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("ABI      : ").append(Build.SUPPORTED_ABIS.joinToString()).append("\n")
                append("=".repeat(60)).append("\n\n")
            },
        )
    }

    private fun append(entry: String) {
        runCatching {
            rotateIfNeeded()
            val bytes = entry.toByteArray(Charsets.UTF_8)
            openStream().apply {
                write(bytes)
                flush()
            }
            writtenBytes += bytes.size
        }.onFailure {
            // 这里不能再走 Timber，否则写日志失败会触发写日志，直接递归
            Log.w(TAG, "写 app 日志失败", it)
            closeStream()
        }
    }

    private fun openStream(): BufferedOutputStream = stream ?: BufferedOutputStream(
        FileOutputStream(currentFile(), true),
        BUFFER_SIZE,
    ).also {
        stream = it
        writtenBytes = currentFile().length()
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        writtenBytes = -1L
    }

    /** 只留一份历史：留多份会在设备上堆出几十 MB，而排障基本只看最近一次 */
    private fun rotateIfNeeded() {
        if (writtenBytes < 0) {
            val file = currentFile()
            if (!file.exists()) return
            writtenBytes = file.length()
        }
        if (writtenBytes < MAX_FILE_BYTES) return

        closeStream()
        val current = currentFile()
        val previous = File(current.parentFile, PREVIOUS_FILE_NAME)
        runCatching {
            if (previous.exists()) previous.delete()
            current.renameTo(previous)
        }
        writtenBytes = 0L
    }

    private fun currentFile(): File = File(logDir().apply { mkdirs() }, CURRENT_FILE_NAME)

    private fun format(priority: Int, tag: String?, message: String, throwable: Throwable?): String {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        return buildString {
            append("[").append(ZonedDateTime.now().format(timestampFormat)).append("] ")
            append(level).append(" ").append(tag ?: "-").append(": ").append(message).append("\n")
            throwable?.let { append(Log.getStackTraceString(it)).append("\n") }
        }
    }

    private companion object {
        const val TAG = "AppLogWriter"
        const val CURRENT_FILE_NAME = "app.log"
        const val PREVIOUS_FILE_NAME = "app.log.1"
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val QUEUE_CAPACITY = 512
    }
}
