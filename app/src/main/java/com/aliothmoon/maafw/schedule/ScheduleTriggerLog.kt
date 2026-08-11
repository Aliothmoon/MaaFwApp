package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.constant.AppPaths
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
 *
 * **只存稳定语义**：[result] 是枚举，文案由 UI 现场解析。这里若存已解析的字符串，
 * 写入那一刻的语言会被冻进文件，之后切语言旧记录还是旧语言；`UiText` 也不能存，
 * 它带的 resId 跨版本会变
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
    /** 仅 [TriggerResult.FAILED_START] 有值；老记录为 null */
    val failureReason: TriggerFailureReason? = null,
    /**
     * 发起过程里每个环境挂载物的落点，按 engage 顺序
     *
     * 只到投递为止——收尾发生在整轮结束之后，那时这条早写完了。
     * 存 hookId 而不是把动作枚举进协议：挂载物可扩展，枚举一次就得改一次 schema
     */
    val steps: List<TriggerStep> = emptyList(),
) {
    /** 派生稳定标识（不入盘）：定位/删除用，避免给序列化类加随机 id 导致旧记录每次解码变值 */
    val stableId: String get() = "$strategyId|$scheduledAt|$actualAt|$result"
}

/** 一步：挂载物 id + 它这一轮的落点 */
@Serializable
data class TriggerStep(
    val hookId: String,
    val outcome: TriggerStepOutcome,
)

@Serializable
enum class TriggerStepOutcome { ENGAGED, SKIPPED, FAILED }

/**
 * 触发日志的读写；单文件追加，超量后从头截断
 *
 * 不做按次分文件（MaaMeow 那样一次触发一个文件）：这里一次触发只有一条记录，
 * 分文件只会在 `/log` 下堆出上千个几十字节的小文件
 */
class ScheduleTriggerLog {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun append(entry: TriggerLogEntry) = withContext(Dispatchers.IO) {
        runCatching {
            val file = logFile()
            file.appendText(json.encodeToString(entry) + "\n")
            if (file.length() > MAX_BYTES) trim(file)
        }.onFailure { Timber.w(it, "Failed to write trigger log") }
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
            Timber.w(it, "Failed to read trigger log")
            emptyList()
        }
    }

    suspend fun delete(stableId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = logFile()
            if (!file.exists()) return@runCatching
            val kept = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString<TriggerLogEntry>(it) }.getOrNull() }
                .filter { it.stableId != stableId }
            if (kept.isEmpty()) file.delete()
            else file.writeText(kept.joinToString("\n", postfix = "\n"))
        }.onFailure { Timber.w(it, "Failed to delete trigger log entry") }
        Unit
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { logFile().delete() }
        Unit
    }

    private fun logFile(): File = File(AppPaths.logDir.apply { mkdirs() }, FILE_NAME)

    /** 保留最近 [KEEP_LINES] 行；整体重写而非原地删，追加写没法从头裁 */
    private fun trim(file: File) {
        runCatching {
            val kept = file.readLines().takeLast(KEEP_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }.onFailure { Timber.w(it, "Failed to trim trigger log") }
    }

    private companion object {
        const val FILE_NAME = "schedule-trigger.log"
        const val MAX_BYTES = 256 * 1024L
        const val KEEP_LINES = 500
    }
}
