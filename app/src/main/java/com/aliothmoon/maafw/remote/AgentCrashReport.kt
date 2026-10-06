package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.runner.AgentExitCode
import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * agent child 被信号杀死时的现场
 *
 * 信号、寄存器、调用栈系统的 debuggerd 已经写进 logcat 的 crash 缓冲区，特权进程是 shell/root 身份读得到，
 * 这里只按 pid 捞出来落盘。tombstone 本身在 `/data/tombstones`，shell 进不去；
 * 环形缓冲只有 1MiB，等用户来反馈时早被冲掉了
 */
internal object AgentCrashReport {

    /** 返回落盘的文件名；缓冲区里没有这个 pid 的记录、或写不进去时为 null */
    fun save(dir: File, executable: String, pid: Int, exitCode: Int): String? {
        val lines = capture(pid)
        if (lines.isEmpty()) {
            Ln.w("AgentCrashReport: no crash buffer entry for pid=$pid")
            return null
        }
        return runCatching {
            dir.mkdirs()
            val now = Date()
            File(dir, "agent_${STAMP_FILE.format(now)}_$pid.txt")
                .apply { writeText(report(now, executable, pid, exitCode, lines)) }
                .name
        }.onFailure {
            Ln.w("AgentCrashReport: write failed: ${it.message}")
        }.getOrNull()
    }

    /** crash_dump 写完日志才放 child 去死，正常一次就捞得到；logd 落后时多等几拍 */
    private fun capture(pid: Int): List<String> {
        var found = emptyList<String>()
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_INTERVAL_MILLIS)
            found = extract(readCrashBuffer(), pid)
            if (found.size > 1) return found
        }
        return found
    }

    private fun readCrashBuffer(): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-b", "crash", "-d", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readLines() }.also { process.waitFor() }
    }.getOrElse {
        Ln.w("AgentCrashReport: logcat failed: ${it.message}")
        emptyList()
    }

    /**
     * 一次崩溃在缓冲区里挂在两个 pid 下：`libc` 那行 Fatal signal 是 child 自己打的，
     * 后面整块 `DEBUG` 是 crash_dump 打的，只有正文里的 `pid: <child>,` 认得出是谁的
     *
     * pid 会复用，取缓冲区里最后一次
     */
    internal fun extract(lines: List<String>, pid: Int): List<String> {
        val entries = lines.mapNotNull(::parse)
        val header = entries.indexOfLast { it.tag == DUMP_TAG && it.message.startsWith("pid: $pid,") }
        val before = if (header < 0) entries else entries.subList(0, header)
        val fatal = before.lastOrNull { it.pid == pid && it.tag == FATAL_TAG }?.raw
        if (header < 0) return listOfNotNull(fatal)

        val dumper = entries[header].pid
        fun isDump(index: Int) = entries[index].pid == dumper && entries[index].tag == DUMP_TAG
        fun isBanner(index: Int) = isDump(index) && entries[index].message.startsWith(DUMP_BANNER)

        val start = (header downTo 0).firstOrNull(::isBanner) ?: header
        val dump = buildList {
            for (index in start until entries.size) {
                if (index > start && isBanner(index)) break
                if (isDump(index)) add(entries[index].raw)
            }
        }
        return listOfNotNull(fatal) + dump
    }

    private class Entry(val pid: Int, val tag: String, val message: String, val raw: String)

    /** threadtime：`MM-DD HH:MM:SS.mmm  PID  TID L TAG     : 正文` */
    private fun parse(raw: String): Entry? {
        val fields = raw.trim().split(WHITESPACE, limit = 6)
        if (fields.size < 6) return null
        val pid = fields[2].toIntOrNull() ?: return null
        val rest = fields[5]
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        return Entry(pid, rest.substring(0, colon).trim(), rest.substring(colon + 1).removePrefix(" "), raw)
    }

    private fun report(now: Date, executable: String, pid: Int, exitCode: Int, lines: List<String>): String =
        buildString {
            append("Time     : ").append(STAMP_READABLE.format(now)).append('\n')
            append("Agent    : ").append(executable).append('\n')
            append("Pid      : ").append(pid).append('\n')
            append("Exit code: ").append(exitCode)
            AgentExitCode.signalOf(exitCode)?.let { append(" (").append(AgentExitCode.signalName(it)).append(')') }
            append('\n')
            append("MaaFwApp : ").append(BuildConfig.MAFW_APP_VERSION).append('\n')
            append('\n')
            lines.forEach { append(it).append('\n') }
        }

    private const val FATAL_TAG = "libc"
    private const val DUMP_TAG = "DEBUG"
    private const val DUMP_BANNER = "*** ***"

    private const val ATTEMPTS = 5
    private const val RETRY_INTERVAL_MILLIS = 200L

    private val WHITESPACE = Regex("\\s+")
    private val STAMP_FILE = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val STAMP_READABLE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}
