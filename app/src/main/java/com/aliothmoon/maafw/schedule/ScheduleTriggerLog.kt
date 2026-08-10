package com.aliothmoon.maafw.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 一次触发的落盘记录，一次一行 JSON
 *
 * 与 `ExecutionResult` 不同，这个必须落盘：闹钟触发时 app 多半没在前台，用户回头查
 * 「昨晚那条到底响没响」只能靠它。文件在外部私有目录，adb pull 拿得到
 */
@Serializable
data class TriggerLogEntry(
    val strategyId: String,
    val strategyName: String,
    /** 闹钟原定时刻 */
    val scheduledAt: Long,
    /** 实际被叫醒的时刻；与上一项的差值就是 Doze 与厂商省电策略的延迟 */
    val actualAt: Long,
    val result: TriggerResult,
    val message: String? = null,
)

/**
 * 触发日志的读写；单文件追加，超量后从头截断
 *
 * 不做按次分文件（MaaMeow 那样一次触发一个文件）：这里一次触发只有一条记录，
 * 分文件只会在 `/log` 下堆出上千个几十字节的小文件
 */
class ScheduleTriggerLog(private val logDir: () -> File) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun append(entry: TriggerLogEntry) = withContext(Dispatchers.IO) {
        runCatching {
            val file = logFile()
            file.appendText(json.encodeToString(entry) + "\n")
            if (file.length() > MAX_BYTES) trim(file)
        }.onFailure { Timber.w(it, "写触发日志失败") }
        Unit
    }

    suspend fun readAll(): List<TriggerLogEntry> = withContext(Dispatchers.IO) {
        val file = logFile()
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString<TriggerLogEntry>(line) }.getOrNull() }
                .asReversed()
        }.getOrElse {
            Timber.w(it, "读触发日志失败")
            emptyList()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { logFile().delete() }
        Unit
    }

    private fun logFile(): File = File(logDir().apply { mkdirs() }, FILE_NAME)

    /** 保留最近 [KEEP_LINES] 行；整体重写而非原地删，追加写没法从头裁 */
    private fun trim(file: File) {
        runCatching {
            val kept = file.readLines().takeLast(KEEP_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }.onFailure { Timber.w(it, "裁剪触发日志失败") }
    }

    private companion object {
        const val FILE_NAME = "schedule-trigger.log"
        const val MAX_BYTES = 256 * 1024L
        const val KEEP_LINES = 500
    }
}
