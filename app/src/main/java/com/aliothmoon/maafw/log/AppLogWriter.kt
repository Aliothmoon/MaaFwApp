package com.aliothmoon.maafw.log

import android.os.Build
import android.util.Log
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.DeviceInfoText.formatGitLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AppLogWriter {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO.limitedParallelism(1))
    private val channel = Channel<String>(capacity = QUEUE_CAPACITY)

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var stream: BufferedOutputStream? = null

    /** -1 表示还没打开过文件，下次写入时从磁盘读实际大小 */
    private var writtenBytes: Long = -1L

    init {
        scope.launch {
            for (entry in channel) {
                append(entry)
                // 逐条 flush 等于每行一次 write 系统调用，下面那个 BufferedOutputStream 就白设了
                while (true) append(channel.tryReceive().getOrNull() ?: break)
                flush()
            }
        }
    }

    fun submit(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        channel.trySend(format(priority, tag, message, throwable))
    }

    fun setup() {
        channel.trySend(
            buildString {
                append("\n").append("=".repeat(60)).append("\n")
                append("Startup time : ").append(ZonedDateTime.now().format(timestampFormat))
                    .append("\n")
                append("Version     : ").append(BuildConfig.VERSION_NAME)
                append(" (").append(BuildConfig.VERSION_CODE).append(") ")
                append(BuildConfig.BUILD_TYPE).append("\n")
                append(formatGitLine("Git (MaaFwApp)", BuildConfig.MAFW_GIT_TAG, BuildConfig.MAFW_GIT_COMMIT)).append("\n")
                append(
                    formatGitLine(
                        "Git (Parent)",
                        BuildConfig.MAFW_PARENT_GIT_TAG,
                        BuildConfig.MAFW_PARENT_GIT_COMMIT,
                        notSubmodule = BuildConfig.MAFW_PARENT_GIT_COMMIT.isEmpty(),
                    ),
                ).append("\n")
                append("Device     : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append("\n")
                append("OS     : Android ").append(Build.VERSION.RELEASE)
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
            openStream().write(bytes)
            writtenBytes += bytes.size
        }.onFailure {
            Log.w(TAG, "Failed to write app log", it)
            closeStream()
        }
    }

    private fun flush() {
        runCatching { stream?.flush() }.onFailure {
            Log.w(TAG, "Failed to flush app log", it)
            closeStream()
        }
    }

    private fun openStream(): BufferedOutputStream = stream ?: BufferedOutputStream(
        FileOutputStream(file(), true),
        BUFFER_SIZE,
    ).also {
        stream = it
        writtenBytes = file().length()
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        writtenBytes = -1L
    }

    private fun rotateIfNeeded() {
        if (writtenBytes < 0) {
            val file = file()
            if (!file.exists()) return
            writtenBytes = file.length()
        }
        if (writtenBytes < MAX_FILE_BYTES) return

        closeStream()
        val dir = AppPaths.LOG_DIR
        runCatching {
            File(dir, rotatedName(MAX_FILES - 1)).delete()
            for (index in MAX_FILES - 2 downTo 1) {
                val from = File(dir, rotatedName(index))
                if (from.exists()) from.renameTo(File(dir, rotatedName(index + 1)))
            }
            file().renameTo(File(dir, rotatedName(1)))
        }
        writtenBytes = 0L
    }

    fun listFiles(): List<File> = runCatching {
        AppPaths.LOG_DIR.listFiles()?.filter { it.isFile && FILE_PATTERN.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
    }.getOrElse {
        Log.w(TAG, "Failed to list app logs", it)
        emptyList()
    }

    fun purge(): Job = scope.launch {
        closeStream()
        runCatching { listFiles().forEach { it.delete() } }.onFailure {
            Log.w(
                TAG,
                "Failed to clear app logs",
                it
            )
        }
    }

    private fun file(): File = File(AppPaths.LOG_DIR.apply { mkdirs() }, CURRENT_FILE_NAME)

    private fun rotatedName(index: Int): String = "app.$index.log"

    private fun format(
        priority: Int, tag: String?, message: String, throwable: Throwable?
    ): String {
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
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val MAX_FILES = 5
        const val QUEUE_CAPACITY = 512

        /** `app.log` 与 `app.<n>.log`；与 [rotatedName] 是一对，改一处要改两处 */
        val FILE_PATTERN = Regex("""app(\.\d+)?\.log""")
    }
}
