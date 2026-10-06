package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.log.LogExportCollector
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 任务级的失败证据：任务开跑时记下各日志的长度与已有的出错截图，任务失败后只取这之后新写的部分
 *
 * 对应 MXU `task_diagnostics.rs`，各项上限相同。日志尾巴走 Sentry Logs，`on_error/` 下新出现的截图
 * 打成 zip 挂到失败事件上；`vision/`、崩溃现场、缓存帧、运行记录、配置一概不碰
 *
 * 文件由特权进程以 shell 身份写，App 未必读得了：读不了的跳过并留一条 warning，不拖垮整条事件
 */
internal object TaskEvidence {

    /** 任务开跑那一刻之前再往回带一点上下文 */
    private const val LOG_PRELUDE_BYTES = 64L * 1024
    private const val MAX_LOG_RAW_BYTES = 1024L * 1024
    private const val MAX_LOG_FILE_BYTES = 512L * 1024
    private const val MAX_LOG_FILES = 128

    /** PNG / JPEG 本身压过了，zip 里原样存，体积就贴着这个数 */
    const val MAX_IMAGE_RAW_BYTES = 5L * 1024 * 1024
    private const val MAX_IMAGE_BUNDLE_BYTES = 5 * 1024 * 1024
    private const val MAX_IMAGE_FILES = 128

    /** 走目录的上限与文件大小无关，单独卡；撞到了记进 warning */
    private const val MAX_DISCOVERY_ENTRIES = 8 * 1024
    private const val MAX_DISCOVERY_DEPTH = 16

    /** 文件系统报的 mtime 可能比任务开跑的墙上时间略早 */
    private const val MTIME_TOLERANCE_MS = 2_000L

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    /** [startedAt] 取在走目录之前：失败得快的任务可能在走目录这会儿就写了截图，那张算它的 */
    fun captureStart(sources: List<EvidenceSource>, startedAt: Long = System.currentTimeMillis()): TaskEvidenceStart {
        val (files, warnings) = discover(sources)
        val logLengths = mutableMapOf<String, Long>()
        val existingImages = mutableMapOf<String, FileStamp>()
        for (found in files) {
            when {
                found.isOnErrorImage() -> {
                    val stamp = found.file.stamp()
                    // 取不到 mtime 的归到底图里：说不清是谁的就不带
                    if (stamp.modified == 0L || stamp.modified < startedAt) existingImages[found.archiveName] = stamp
                }
                found.isLog() -> logLengths[found.archiveName] = found.file.length()
            }
        }
        return TaskEvidenceStart(sources, startedAt, logLengths, existingImages, warnings)
    }

    /**
     * 任务终局一到就把日志边界和已经看得见的截图定下来，句柄此刻就开好：
     * 后面读的时候日志可能已经轮转，按路径再找会找到另一个文件
     */
    fun captureEnd(start: TaskEvidenceStart): TaskEvidenceSelection {
        val (files, discoveryWarnings) = discover(start.sources)
        val warnings = (start.warnings + discoveryWarnings).toMutableList()
        val logs = mutableListOf<LogSlice>()
        for (found in files) {
            if (found.isOnErrorImage() || !found.isLog()) continue
            if (logs.size >= MAX_LOG_FILES) {
                warnings.addOnce("log_file_limit_reached:$MAX_LOG_FILES")
                break
            }
            selectLog(found, start, warnings)?.let(logs::add)
        }
        val images = selectImages(files, start, warnings)
        return TaskEvidenceSelection(start, logs, images, warnings)
    }

    /** 失败后那一小段等待里再看一眼截图：MaaFramework 可能在终局回调之后才把图写完 */
    fun scanImages(selection: TaskEvidenceSelection): ImageScan {
        val (files, warnings) = discover(selection.start.sources)
        return ImageScan(selectImages(files, selection.start, warnings), warnings)
    }

    /**
     * 各文件最多 512 KiB、合计 1 MiB，都取尾巴：失败的细节通常写在最后
     *
     * 撞到上限体现在 [DiagnosticLogs.truncated]
     */
    fun buildLogs(selection: TaskEvidenceSelection): DiagnosticLogs {
        val warnings = selection.warnings.toMutableList()
        val entries = mutableListOf<DiagnosticLog>()
        var selectedRawBytes = 0L
        var truncated = false
        for (slice in selection.logs.sortedWith(compareBy({ logPriority(it.archiveName) }, { it.archiveName }))) {
            val remaining = MAX_LOG_RAW_BYTES - selectedRawBytes
            if (remaining <= 0) {
                truncated = true
                break
            }
            val selectedLength = slice.end - slice.start
            val readLength = minOf(selectedLength, MAX_LOG_FILE_BYTES, remaining)
            if (readLength <= 0) continue
            val bytes = try {
                slice.source.readRange(slice.end - readLength, readLength.toInt())
            } catch (e: IOException) {
                warnings += "read_log_failed:${slice.archiveName}:${e.message}"
                continue
            }
            val content = String(bytes, Charsets.UTF_8).trim('\u0000')
            if (content.isBlank()) continue
            selectedRawBytes += bytes.size
            truncated = truncated || readLength < selectedLength
            entries += DiagnosticLog(slice.archiveName, logKind(slice.archiveName), content, bytes.size.toLong())
        }
        return DiagnosticLogs(entries, selectedRawBytes, truncated, warnings.distinct().sorted())
    }

    fun buildImageBundle(selection: TaskEvidenceSelection, runId: String, taskId: Long?): AttachmentOutcome {
        val images = selection.images
        if (images.isEmpty()) return AttachmentOutcome.Omitted("no_evidence", "no task-scoped evidence was found")
        val selectedRawBytes = images.sumOf { it.length }
        if (selectedRawBytes > MAX_IMAGE_RAW_BYTES) return tooLarge("raw_too_large", selectedRawBytes, null)

        val buffer = ByteArrayOutputStream(selectedRawBytes.toInt() + 1024)
        try {
            ZipOutputStream(buffer).use { zip ->
                // 再压一遍只费 CPU，截图常常还会变大一点
                zip.setLevel(Deflater.NO_COMPRESSION)
                for (image in images) {
                    zip.putNextEntry(ZipEntry(image.archiveName))
                    zip.write(image.source.readRange(0, image.length.toInt()))
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            return AttachmentOutcome.Omitted("build_failed", "write screenshot zip: ${e.message}")
        }
        val bytes = buffer.toByteArray()
        if (bytes.size > MAX_IMAGE_BUNDLE_BYTES) return tooLarge("bundle_too_large", selectedRawBytes, bytes.size)
        return AttachmentOutcome.Attached(
            bytes = bytes,
            filename = "maafwapp-task-failure-$runId-${taskId ?: 0}-screenshots.zip",
            imageCount = images.size,
            selectedRawBytes = selectedRawBytes,
        )
    }

    private fun tooLarge(status: String, selectedRawBytes: Long, bundleBytes: Int?) = AttachmentOutcome.Omitted(
        status = status,
        detail = "task screenshot evidence is too large (raw=$selectedRawBytes, bundle=${bundleBytes ?: "not-built"})",
        selectedRawBytes = selectedRawBytes,
        bundleBytes = bundleBytes,
    )

    private fun selectLog(found: Found, start: TaskEvidenceStart, warnings: MutableList<String>): LogSlice? {
        val name = found.archiveName
        val length = found.file.length()
        val modified = modifiedSince(found.file.lastModified(), start.startedAt)
        val previous = start.logLengths[name]
        val from = when {
            length == 0L -> return null
            previous == null -> {
                if (!modified) return null
                warnings += "new_log_included_whole:$name"
                0L
            }
            length > previous -> (previous - LOG_PRELUDE_BYTES).coerceAtLeast(0)
            // 开跑快照还在走目录时任务就把失败日志写完了，长度没变不等于没写，留一截尾巴
            length == previous && modified -> {
                warnings += "log_changed_during_start_snapshot:$name"
                (length - LOG_PRELUDE_BYTES).coerceAtLeast(0)
            }
            length < previous && modified -> {
                warnings += "rotated_log_included_whole:$name"
                0L
            }
            else -> return null
        }
        val source = found.open(warnings, "open_log_failed") ?: return null
        // 终点以开好的句柄为准，与上面按路径量的那一下之间文件可能又长了或者换了
        val end = runCatching { source.length() }.getOrDefault(0L)
        if (end <= from) {
            source.closeQuietly()
            return null
        }
        return LogSlice(source, name, from, end)
    }

    private fun selectImages(
        files: List<Found>,
        start: TaskEvidenceStart,
        warnings: MutableList<String>,
    ): MutableList<ImageEntry> {
        val images = mutableListOf<ImageEntry>()
        for (found in files) {
            if (!found.isOnErrorImage()) continue
            val stamp = found.file.stamp()
            if (!modifiedSince(stamp.modified, start.startedAt)) continue
            if (start.existingImages[found.archiveName] == stamp) continue
            if (images.size >= MAX_IMAGE_FILES) {
                warnings.addOnce("image_file_limit_reached:$MAX_IMAGE_FILES")
                break
            }
            val source = found.open(warnings, "open_image_failed") ?: continue
            images += ImageEntry(source, found.archiveName, stamp.length, stamp)
        }
        images.sortBy { it.archiveName }
        return images
    }

    private class Found(val file: File, val relative: String, val source: EvidenceSource) {
        val archiveName: String get() = source.prefix + relative

        private val topDir: String get() = relative.substringBefore('/', "").lowercase()

        fun isOnErrorImage(): Boolean =
            source.images && topDir == "on_error" && file.extension.lowercase() in IMAGE_EXTENSIONS

        fun isLog(): Boolean {
            if (topDir == "on_error" || topDir == "vision") return false
            val name = file.name.lowercase()
            return (name.endsWith(".log") || name.contains(".log.")) && source.accepts(relative)
        }

        fun open(warnings: MutableList<String>, warning: String): RandomAccessFile? =
            try {
                RandomAccessFile(file, "r")
            } catch (e: IOException) {
                warnings.addOnce("$warning:$archiveName")
                null
            }
    }

    private fun discover(sources: List<EvidenceSource>): Pair<List<Found>, MutableList<String>> {
        val files = mutableListOf<Found>()
        val warnings = mutableListOf<String>()
        var visited = 0
        for (source in sources) {
            if (!source.root.isDirectory) continue
            val pending = ArrayDeque<Pair<File, Int>>().apply { add(source.root to 0) }
            while (pending.isNotEmpty()) {
                val (directory, depth) = pending.removeLast()
                val entries = directory.listFiles()
                if (entries == null) {
                    warnings.addOnce("discovery_read_directory_failed")
                    continue
                }
                for (entry in entries) {
                    if (visited >= MAX_DISCOVERY_ENTRIES) {
                        warnings.addOnce("discovery_entry_limit_reached:$MAX_DISCOVERY_ENTRIES")
                        return files to warnings
                    }
                    visited++
                    if (Files.isSymbolicLink(entry.toPath())) {
                        warnings.addOnce("discovery_symlink_skipped")
                        continue
                    }
                    when {
                        entry.isDirectory -> when {
                            depth == 0 && entry.name.lowercase() in source.skipDirs -> Unit
                            depth >= MAX_DISCOVERY_DEPTH -> warnings.addOnce("discovery_depth_limit_reached:$MAX_DISCOVERY_DEPTH")
                            else -> pending.add(entry to depth + 1)
                        }
                        entry.isFile -> files += Found(entry, entry.relativeTo(source.root).invariantSeparatorsPath, source)
                    }
                }
            }
        }
        return files to warnings
    }

    private fun modifiedSince(modified: Long, startedAt: Long): Boolean =
        modified != 0L && modified + MTIME_TOLERANCE_MS >= startedAt

    private fun File.stamp() = FileStamp(length(), lastModified())

    /** 文件可能在量完之后被截短，读到多少算多少 */
    private fun RandomAccessFile.readRange(from: Long, length: Int): ByteArray {
        seek(from)
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = read(buffer, filled, length - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == length) buffer else buffer.copyOf(filled)
    }

    /** 框架日志排最前，预算不够时先保它；外壳自己的 `app.log` 对应 MXU 的 `mxu` 那一档 */
    private fun logPriority(archiveName: String): Int {
        val name = archiveName.substringAfterLast('/').lowercase()
        return when {
            name.startsWith("maafw.") -> 0
            name.startsWith("maa.log") -> 1
            name.startsWith("app.") -> 2
            else -> 3
        }
    }

    private fun logKind(archiveName: String): String = when (logPriority(archiveName)) {
        0 -> "maafw"
        1 -> "maa"
        2 -> "maafwapp"
        else -> "other"
    }
}

/** 一棵要取证的目录 */
internal class EvidenceSource(
    val root: File,
    /** 归档名前缀，带结尾的 `/`；外壳自己的日志树为空 */
    val prefix: String = "",
    /** 这棵树下不往里走的顶层目录，小写 */
    val skipDirs: Set<String> = emptySet(),
    /** 相对 [root] 的路径还要过这一关才算日志 */
    val accepts: (relative: String) -> Boolean = { true },
    /** 只有 MaaFramework 的日志目录下才有 `on_error/` */
    val images: Boolean = false,
) {
    companion object {
        /** 这些顶层目录里没有任务日志，有的还会堆很多文件，不值得每个任务都走一遍 */
        private val SHELL_LOG_SKIP_DIRS = setOf("vision", "focus", "crash", "run", "export")

        /**
         * 外壳的日志树整棵看；agent 的日志写在 PI 根下，只看配方 `logs.include` 点名的，
         * 而且从 glob 里不含通配符的那段目录开始走——PI 根下的资源文件成千上万
         */
        fun of(logDir: File, piRoot: File, piLogInclude: List<String>): List<EvidenceSource> {
            val shell = EvidenceSource(root = logDir, skipDirs = SHELL_LOG_SKIP_DIRS, images = true)
            val agents = piLogInclude.groupBy(::staticPrefix).map { (prefix, globs) ->
                val patterns = globs.map(LogExportCollector::globToRegex)
                val base = if (prefix.isEmpty()) "" else "$prefix/"
                EvidenceSource(
                    root = if (prefix.isEmpty()) piRoot else File(piRoot, prefix),
                    prefix = "pi/$base",
                    accepts = { relative -> patterns.any { it.matches(base + relative) } },
                )
            }
            return listOf(shell) + agents
        }

        private fun staticPrefix(glob: String): String =
            glob.split('/').dropLast(1).takeWhile { '*' !in it && '?' !in it }.joinToString("/")
    }
}

internal data class FileStamp(val length: Long, /** 0 = 取不到 */ val modified: Long)

internal class TaskEvidenceStart(
    val sources: List<EvidenceSource>,
    val startedAt: Long,
    /** 归档名 → 任务开跑时的长度 */
    val logLengths: Map<String, Long>,
    /** 任务开跑前就在的截图 */
    val existingImages: Map<String, FileStamp>,
    val warnings: List<String>,
)

internal class LogSlice(val source: RandomAccessFile, val archiveName: String, val start: Long, val end: Long)

internal class ImageEntry(val source: RandomAccessFile, val archiveName: String, val length: Long, val stamp: FileStamp)

internal class ImageScan(val images: List<ImageEntry>, val warnings: List<String>) : Closeable {
    override fun close() = images.forEach { it.source.closeQuietly() }
}

/** 定下来的一份证据；手里攥着打开的句柄，用完要 [close] */
internal class TaskEvidenceSelection(
    val start: TaskEvidenceStart,
    val logs: List<LogSlice>,
    images: List<ImageEntry>,
    warnings: List<String>,
) : Closeable {
    var images: List<ImageEntry> = images
        private set
    var warnings: List<String> = warnings.distinct().sorted()
        private set

    /** 同一代的文件留着原来的句柄，长了或换了的升到新扫到的那一代；[scan] 的句柄此后归这里管 */
    fun merge(scan: ImageScan) {
        val merged = images.associateByTo(mutableMapOf()) { it.archiveName }
        for (image in scan.images) {
            val existing = merged[image.archiveName]
            if (existing != null && existing.stamp == image.stamp) {
                image.source.closeQuietly()
            } else {
                existing?.source?.closeQuietly()
                merged[image.archiveName] = image
            }
        }
        images = merged.values.sortedBy { it.archiveName }
        warnings = (warnings + scan.warnings).distinct().sorted()
    }

    override fun close() {
        logs.forEach { it.source.closeQuietly() }
        images.forEach { it.source.closeQuietly() }
    }
}

internal class DiagnosticLog(
    /** 归档名，如 `maafw.log`、`pi/debug/go-service.log` */
    val source: String,
    val kind: String,
    val content: String,
    val rawBytes: Long,
)

internal class DiagnosticLogs(
    val entries: List<DiagnosticLog>,
    val selectedRawBytes: Long,
    val truncated: Boolean,
    val warnings: List<String>,
)

/** 截图附件的去向，原样写进事件的 `attachment.*` */
internal sealed interface AttachmentOutcome {
    /** 没被采样到，或者压根没取证 */
    data object NotSelected : AttachmentOutcome

    class Attached(
        val bytes: ByteArray,
        val filename: String,
        val imageCount: Int,
        val selectedRawBytes: Long,
    ) : AttachmentOutcome

    class Omitted(
        val status: String,
        val detail: String,
        val selectedRawBytes: Long? = null,
        val bundleBytes: Int? = null,
    ) : AttachmentOutcome
}

private fun MutableList<String>.addOnce(warning: String) {
    if (warning !in this) add(warning)
}

private fun Closeable.closeQuietly() {
    runCatching { close() }
}
