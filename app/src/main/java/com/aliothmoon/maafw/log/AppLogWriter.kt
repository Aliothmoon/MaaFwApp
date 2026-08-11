package com.aliothmoon.maafw.log

import android.os.Build
import android.util.Log
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
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
class AppLogWriter {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO.limitedParallelism(1))
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
                append("Startup time : ").append(ZonedDateTime.now().format(timestampFormat)).append("\n")
                append("Version     : ").append(BuildConfig.VERSION_NAME)
                append(" (").append(BuildConfig.VERSION_CODE).append(") ")
                append(BuildConfig.BUILD_TYPE).append("\n")
                append("Device     : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
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
            openStream().apply {
                write(bytes)
                flush()
            }
            writtenBytes += bytes.size
        }.onFailure {
            // 这里不能再走 Timber，否则写日志失败会触发写日志，直接递归
            Log.w(TAG, "Failed to write app log", it)
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

    /**
     * 满一份就整体后移一位，最老的丢掉
     *
     * 留 [MAX_FILES] 份而不是一份：一次长跑几分钟就能把 4MB 写满，只留一份的话
     * 用户回头来看时现场早被后来的日志顶掉了
     */
    private fun rotateIfNeeded() {
        if (writtenBytes < 0) {
            val file = currentFile()
            if (!file.exists()) return
            writtenBytes = file.length()
        }
        if (writtenBytes < MAX_FILE_BYTES) return

        closeStream()
        val dir = AppPaths.logDir
        runCatching {
            File(dir, rotatedName(MAX_FILES - 1)).delete()
            for (index in MAX_FILES - 2 downTo 1) {
                val from = File(dir, rotatedName(index))
                if (from.exists()) from.renameTo(File(dir, rotatedName(index + 1)))
            }
            currentFile().renameTo(File(dir, rotatedName(1)))
        }
        writtenBytes = 0L
    }

    /** 当前那份在最前，其余按新旧；错误日志页与导出都吃这个顺序 */
    fun listFiles(): List<File> = runCatching {
        AppPaths.logDir.listFiles()
            ?.filter { it.isFile && FILE_PATTERN.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }.getOrElse {
        Log.w(TAG, "Failed to list app logs", it)
        emptyList()
    }

    /**
     * 走同一条通道，保证与写入串行——直接删会把正在写的那个流的文件描述符悬空
     *
     * 返回 Job 让调用方 join 得到：删完再刷新列表，否则刷出来的还是旧的那几份
     */
    fun clearAll(): Job = scope.launch {
        closeStream()
        runCatching { listFiles().forEach { it.delete() } }
            .onFailure { Log.w(TAG, "Failed to clear app logs", it) }
    }

    private fun currentFile(): File = File(AppPaths.logDir.apply { mkdirs() }, CURRENT_FILE_NAME)

    private fun rotatedName(index: Int): String = "app.$index.log"

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
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val MAX_FILES = 5
        const val QUEUE_CAPACITY = 512

        /** `app.log` 与 `app.<n>.log`；与 [rotatedName] 是一对，改一处要改两处 */
        val FILE_PATTERN = Regex("""app(\.\d+)?\.log""")
    }
}
