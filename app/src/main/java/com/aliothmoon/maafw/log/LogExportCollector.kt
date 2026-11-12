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

    /**
     * 会无限长的那几个目录只留近 7 天；其余（app.log、maa.log、触发日志）本身就有上限，全带
     *
     * 份数没上限的还要过体积预算，见 [plan]
     */
    const val ROLLING_KEEP_DAYS = 7

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 按次或按轮堆文件的目录
     *
     * 加新目录时记得往这里补一条，否则一年后的导出包会有上千个文件
     */
    private val ROLLING_MARKERS = listOf("/run/", "/focus/", "/manual/", "/logcat/", "/crash/")

    /**
     * agent 以 PI 根为工作目录，日志写在它下面；外壳不知道这些文件会不会轮转，一律只收近 7 天
     *
     * [include] 是相对 [root] 的 glob，来自配方的 `logs.include`
     */
    data class PiLogs(val root: File, val include: List<String>)

    /** MaaFramework 轮转出的备份，份数不设上限 */
    private val MAAFW_BACKUP = Regex("""maafw\.bak\..+\.log""")

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    /**
     * 一次导出怎么装：[required] 全带；[optional] 按体积预算从头装，装不下的丢
     *
     * [optional] 已排好：各目录轮流出最新一份，免得一个目录挤掉别的
     */
    data class Plan(val required: List<File>, val optional: List<File>)

    /** 当前的 `maafw.log` 算必带：失败现场在它的尾巴上，刚轮转完的话在最新那份备份里 */
    fun plan(files: List<File>): Plan {
        val (optional, required) = files.partition { file ->
            isRolling(file.invariantSeparatorsPath) ||
                MAAFW_BACKUP.matches(file.name) ||
                file.extension.lowercase() in IMAGE_EXTENSIONS
        }
        return Plan(required, newestFirstAcrossDirs(optional))
    }

    private fun newestFirstAcrossDirs(files: List<File>): List<File> {
        val modified = files.associateWith { it.lastModified() }
        val newest = compareByDescending<File> { modified.getValue(it) }
        return files.groupBy { it.parentFile }.values
            .flatMap { it.sortedWith(newest).withIndex() }
            .sortedWith(compareBy<IndexedValue<File>> { it.index }.thenBy(newest) { it.value })
            .map { it.value }
    }

    fun collect(roots: List<File>, now: Long, piLogs: PiLogs? = null): List<File> {
        val rollingCutoff = now - ROLLING_KEEP_DAYS * MS_PER_DAY
        val own = roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile }
            .filter { shouldExport(it, rollingCutoff) }
        val agents = piLogs?.let(::piLogFiles).orEmpty().filter { it.lastModified() >= rollingCutoff }
        return (own + agents).toList()
    }

    private fun shouldExport(file: File, rollingCutoff: Long): Boolean {
        val path = file.invariantSeparatorsPath
        // 上一次导出的 zip 不能再打进这一次，否则每导一次体积翻一倍
        if (path.contains("/$EXPORT_DIR_NAME/")) return false
        if (!isRolling(path)) return true
        return file.lastModified() >= rollingCutoff
    }

    private fun isRolling(path: String): Boolean = ROLLING_MARKERS.any { path.contains(it) }

    /** 从各 glob 里不含通配符的那段目录开始走：PI 根下的资源文件成千上万 */
    fun piLogFiles(piLogs: PiLogs): Sequence<File> {
        if (piLogs.include.isEmpty() || !piLogs.root.isDirectory) return emptySequence()
        val patterns = piLogs.include.map(::globToRegex)
        val prefixes = piLogs.include.map(::staticPrefix).distinct()
        // 套在别的前缀里面的不再单走，免得同一棵子树走两遍
        val starts = prefixes.filterNot { p -> prefixes.any { q -> q != p && (q.isEmpty() || p.startsWith("$q/")) } }
        return starts.asSequence()
            .map { File(piLogs.root, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { file ->
                val relative = file.relativeTo(piLogs.root).invariantSeparatorsPath
                patterns.any { it.matches(relative) }
            }
            .filter { it.isFile }
    }

    internal fun staticPrefix(glob: String): String =
        glob.split('/').dropLast(1).takeWhile { '*' !in it && '?' !in it }.joinToString("/")

    /** 与配方 `include` 同一套写法：`**` 跨目录，`*`、`?` 不跨 `/` */
    internal fun globToRegex(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 3
                }
                glob.startsWith("**", i) -> {
                    out.append(".*")
                    i += 2
                }
                else -> {
                    out.append(
                        when (val c = glob[i]) {
                            '*' -> "[^/]*"
                            '?' -> "[^/]"
                            else -> Regex.escape(c.toString())
                        },
                    )
                    i++
                }
            }
        }
        return Regex(out.toString())
    }
}
