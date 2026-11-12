package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.log.LogExportCollector
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * MaaFramework 轮转出的 `maafw.bak.*.log` 的保留策略：每个目录最新几份不动，更早的按天数、再按总量从旧到新删
 *
 * 框架只在 `maafw.log` 过 16MB 时复制出一份备份，份数不设上限，自己只清 7 天前的；
 * 跑一轮全套日常就能滚出上百 MB。`maafw.log` 框架正写着，不碰
 *
 * 宁可少删：只认框架原样的文件名，同目录得有 `maafw.log`，真实路径得在根之内
 *
 * 放在特权进程：日志是它和它拉起的 agent 以 shell 身份写的
 */
internal object MaafwLogRetention {

    /** 每个目录最新的这几份无论多老多大都留着：现场多半在最近一轮里 */
    private const val MIN_KEEP = 3

    private const val KEEP_DAYS = 3L

    /** 外壳 tasker 的日志目录：大约一轮全套日常的量 */
    private const val SHELL_MAX_TOTAL_BYTES = 128L * 1024 * 1024

    private const val AGENT_MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 框架的命名 `maafw.bak.{yyyy.MM.dd-HH.mm.ss.SSS}.log`，比导出时认的更严 */
    private val BACKUP_NAME = Regex("""maafw\.bak\.\d{4}\.\d{2}\.\d{2}-\d{2}\.\d{2}\.\d{2}\.\d{3}\.log""")

    private const val CURRENT_LOG = "maafw.log"

    data class Entry(val file: File, val lastModified: Long, val size: Long)

    /** [entries] 是同一目录下的备份；总量把留着的最新几份也算进去 */
    fun selectExpired(entries: List<Entry>, now: Long, maxTotalBytes: Long): List<File> {
        val cutoff = now - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        var total = entries.sumOf { it.size }
        val doomed = ArrayList<File>()
        for (entry in entries.sortedBy { it.lastModified }.dropLast(MIN_KEEP)) {
            if (entry.lastModified >= cutoff && total <= maxTotalBytes) break
            doomed += entry.file
            total -= entry.size
        }
        return doomed
    }

    /**
     * 外壳的日志目录只看顶层；agent 的按配方 `logs.include` 找，同一目录下的备份算一组
     *
     * 返回删掉的份数
     */
    fun prune(shellLogDir: File?, piLogs: LogExportCollector.PiLogs?, now: Long = System.currentTimeMillis()): Int {
        val shellRoot = shellLogDir?.takeIf { it.isDirectory }?.canonicalFile
        val piRoot = piLogs?.root?.takeIf { it.isDirectory }?.canonicalFile
        val shellBackups = shellRoot?.listFiles()?.asList().orEmpty()
        val agentBackups = piLogs?.let { LogExportCollector.piLogFiles(it) }.orEmpty().toList()
        return (shellBackups + agentBackups)
            // 先查软链再取真实路径，取完就看不出来了
            .filter(::isFrameworkBackup)
            .map { it.canonicalFile }
            .distinct()
            .groupBy { it.parentFile }
            .entries
            .sumOf { (dir, files) ->
                val budget = when {
                    dir == shellRoot -> SHELL_MAX_TOTAL_BYTES
                    piRoot != null && dir.startsWith(piRoot) -> AGENT_MAX_TOTAL_BYTES
                    // 目录软链把路径带出了根，不认
                    else -> return@sumOf 0
                }
                selectExpired(files.map { Entry(it, it.lastModified(), it.length()) }, now, budget)
                    .count { it.delete() }
            }
    }

    private fun isFrameworkBackup(file: File): Boolean =
        BACKUP_NAME.matches(file.name) &&
            file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            File(file.parentFile, CURRENT_LOG).isFile
}
