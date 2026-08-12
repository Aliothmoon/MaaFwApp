package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一次运行落盘成一个文件，一行一条 JSON
 *
 * 这是**诊断产物**不是领域状态：只写不读回，除了给人看与打包导出，没有任何代码路径
 * 拿它当输入（docs/persistence-diagnostics.md §2）。执行结果仍旧不进 `UserConfiguration`
 *
 * 一行一条而不是整个数组：跑到一半被系统杀掉时，已经落下的行照样解得出来
 */
@Serializable
sealed interface RunSessionRecord {

    @Serializable
    @SerialName("header")
    data class Header(val startedAt: Long, val tasks: List<String>) : RunSessionRecord

    /**
     * [text] 是**已渲染的成品文本**，写入那一刻的语言被冻进文件
     *
     * 与 `TriggerLogEntry` 存稳定枚举的做法相反，是有意的：运行日志的正文有相当一部分
     * 本来就是 MaaFramework 与 agent 的原文（不参与本地化），只有合成过的那几句能翻译，
     * 为这几句建一张跨版本稳定的 key 表不划算。同一取舍见 `UiText` 的「或原文」
     */
    @Serializable
    @SerialName("line")
    data class Line(
        val atMillis: Long,
        val kind: RunLogKind,
        val text: String,
        /** 原样 details_json；只有调试模式下才写，平时它占掉文件的绝大部分体积 */
        val detail: String? = null,
    ) : RunSessionRecord

    @Serializable
    @SerialName("footer")
    data class Footer(val endedAt: Long, val outcome: RunSessionOutcome) : RunSessionRecord
}

/** 收尾时写进 Footer 的结局；[NOT_RUN] 表示没投出去就结束了 */
@Serializable
enum class RunSessionOutcome {
    COMPLETED,
    COMPLETED_WITH_FAILURES,
    CANCELLED,
    FAILED,
    NOT_RUN,
}

fun RunEndReason.toSessionOutcome(): RunSessionOutcome = when (this) {
    is RunEndReason.NotRun -> RunSessionOutcome.NOT_RUN
    is RunEndReason.Ran -> when (result) {
        is ExecutionResult.Completed -> RunSessionOutcome.COMPLETED
        is ExecutionResult.CompletedWithFailures -> RunSessionOutcome.COMPLETED_WITH_FAILURES
        is ExecutionResult.Cancelled -> RunSessionOutcome.CANCELLED
        is ExecutionResult.Failed -> RunSessionOutcome.FAILED
    }
}

/**
 * 列表页要显示的那几项，全部从文件名与 stat 得来
 *
 * 不读文件内容：列表可能有上百个文件，为了显示一行摘要去解析每个文件的头是纯浪费
 */
data class RunSessionLogFile(
    val fileName: String,
    val startedAt: Long,
    val sizeBytes: Long,
    val taskCount: Int,
)

class RunSessionLogStore {

    /** 打开一个新会话；返回 null 表示建不出文件，调用方照常跑，只是这轮没有历史记录 */
    suspend fun open(startedAt: Long, tasks: List<String>): RunSessionWriter? =
        withContext(MaaDispatchers.IO) {
            runCatching {
                val stamp = Instant.ofEpochMilli(startedAt)
                    .atZone(ZoneId.systemDefault())
                    .format(FILE_STAMP)
                val file = File(sessionDir(), "$PREFIX$stamp${SEPARATOR}${tasks.size}$SUFFIX")
                val writer = RunSessionWriter(BufferedWriter(FileWriter(file, true)))
                writer.write(listOf(RunSessionRecord.Header(startedAt, tasks)))
                writer
            }.onFailure { Timber.w(it, "开会话日志文件失败") }.getOrNull()
        }

    suspend fun list(): List<RunSessionLogFile> = withContext(MaaDispatchers.IO) {
        runCatching {
            sessionDir().listFiles()
                ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
                ?.map(::describe)
                ?.sortedByDescending { it.startedAt }
                .orEmpty()
        }.getOrElse {
            Timber.w(it, "列会话日志失败")
            emptyList()
        }
    }

    /** 解不出的行跳过而不是整份作废：被杀进程留下的半行不该毁掉前面几百条 */
    suspend fun read(fileName: String): List<RunSessionRecord> = withContext(MaaDispatchers.IO) {
        val file = File(sessionDir(), fileName)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { JSON.decodeFromString<RunSessionRecord>(line) }.getOrNull()
                }
        }.getOrElse {
            Timber.w(it, "读会话日志失败：%s", fileName)
            emptyList()
        }
    }

    suspend fun delete(fileName: String): Boolean = withContext(MaaDispatchers.IO) {
        runCatching { File(sessionDir(), fileName).delete() }.getOrDefault(false)
    }

    /** 返回删掉的份数 */
    suspend fun cleanup(keepDays: Int = KEEP_DAYS): Int = withContext(MaaDispatchers.IO) {
        val cutoff = System.currentTimeMillis() - keepDays * MS_PER_DAY
        list().count { it.startedAt < cutoff && delete(it.fileName) }
    }

    /**
     * 文件名形如 `run_20260811_143052_3.jsonl`
     *
     * 解不出时间戳就退回 mtime：文件仍然要能列出来、能删、能导出
     */
    private fun describe(file: File): RunSessionLogFile {
        val parts = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).split(SEPARATOR)
        val startedAt = runCatching {
            LocalDateTime.parse("${parts[0]}$SEPARATOR${parts[1]}", FILE_STAMP)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(file.lastModified())
        return RunSessionLogFile(
            fileName = file.name,
            startedAt = startedAt,
            sizeBytes = file.length(),
            taskCount = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }

    private fun sessionDir(): File = File(AppPaths.LOG_DIR, DIR_NAME).apply { mkdirs() }

    private companion object {
        const val DIR_NAME = "run"
        const val PREFIX = "run_"
        const val SUFFIX = ".jsonl"
        const val SEPARATOR = "_"
        const val KEEP_DAYS = 30
        const val MS_PER_DAY = 24L * 60 * 60 * 1000

        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd${SEPARATOR}HHmmss")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 一个已打开的会话文件
 *
 * 不自带节流：攒批与冲洗周期归 [RunLogRecorder] 管，这里只管把给的东西写下去。
 * 整批只 flush 一次——逐条 flush 会让 [BufferedWriter] 完全失去意义
 */
class RunSessionWriter internal constructor(private val writer: BufferedWriter) {

    private val json = Json { encodeDefaults = true }

    fun write(records: List<RunSessionRecord>) {
        if (records.isEmpty()) return
        runCatching {
            records.forEach { record ->
                writer.write(json.encodeToString(RunSessionRecord.serializer(), record))
                writer.newLine()
            }
            writer.flush()
        }.onFailure { Timber.w(it, "写会话日志失败") }
    }

    fun close() {
        runCatching { writer.close() }.onFailure { Timber.w(it, "关会话日志失败") }
    }
}
