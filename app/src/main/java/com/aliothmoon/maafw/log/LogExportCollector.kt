package com.aliothmoon.maafw.log

import java.io.File

/**
 * 挑出要打进 zip 的文件；不删源、不写盘，纯函数好测
 *
 * 我们的日志分在两棵目录下（`log/` 与 `debug/`），有意不归拢成一棵：`debug/` 那份的路径
 * 在特权进程侧是硬解析的（见 `RemoteBootTrace`），挪了要连着改两边
 */
object LogExportCollector {

    const val EXPORT_DIR_NAME = "export"

    /** 会无限长的那几个目录只留近 7 天；其余（app.log、maa.log、触发日志）本身就有上限，全带 */
    const val ROLLING_KEEP_DAYS = 7

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 按次或按轮堆文件的目录
     *
     * 加新目录时记得往这里补一条，否则一年后的导出包会有上千个文件
     */
    private val ROLLING_MARKERS = listOf("/run/", "/focus/", "/manual/", "/logcat/", "/crash/")

    fun collect(roots: List<File>, now: Long): List<File> =
        roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile }
            .filter { shouldExport(it, now - ROLLING_KEEP_DAYS * MS_PER_DAY) }
            .toList()

    private fun shouldExport(file: File, rollingCutoff: Long): Boolean {
        val path = file.invariantSeparatorsPath
        // 上一次导出的 zip 不能再打进这一次，否则每导一次体积翻一倍
        if (path.contains("/$EXPORT_DIR_NAME/")) return false
        if (ROLLING_MARKERS.none { path.contains(it) }) return true
        return file.lastModified() >= rollingCutoff
    }
}
