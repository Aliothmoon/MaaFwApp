package com.aliothmoon.maafw.project

import android.content.Context
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.log.LogExportCollector
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * 打包进 APK 的 PI 只读包
 * 不复用 ProjectSource：解包要按字节搬运图片与模型，read(String) 的文本语义不够用
 */
interface PiPackage {
    /** 无归档时按这条逐个 [open]；生产走 [openArchive]，清单不参与解包 */
    fun manifest(): List<String>

    fun open(path: String): InputStream

    /** PI 归档；解包按 zip 顺序扫一遍。单测内存包没有这份 */
    fun openArchive(): InputStream? = null
}

/** zip-slip / 缺根文件：解包失败，由 [PiInstallCoordinator] 收成 Failed */
class PiUnpackException(message: String) : IOException(message)

/** [PiInstaller.installedDir] 在标记未提交时抛；不解包 */
class PiNotInstalledException(dir: File) : IllegalStateException("PI is not installed: ${dir.absolutePath}")

/** 路径一律相对 PI 根，与 [ProjectSource] 保持同一套相对路径语义 */
class AssetPiPackage(context: Context) : PiPackage {

    private val assets = context.applicationContext.assets

    override fun manifest(): List<String> = emptyList()

    override fun open(path: String): InputStream = throw FileNotFoundException(path)

    override fun openArchive(): InputStream? = try {
        assets.open(PI_ARCHIVE_ASSET)
    } catch (_: FileNotFoundException) {
        null
    }
}

/**
 * 逐条目的解包进度；done 从 1 起计
 * 归档扫 zip 时不知道总数，[total] 为 0；单测内存包 [total] 是清单条数
 */
typealias PiUnpackProgress = (done: Int, total: Int, path: String) -> Unit

/**
 * 把打包的 PI 解包到应用外部私有目录
 * native MaaFramework 只认文件系统路径，而 APK 内的 assets 条目不是文件；落点不能用 filesDir——
 * 特权进程是 shell 身份，进不去 0700 的 app 私有目录（docs/privileged-runtime.md §9）
 *
 * 标记文件记 versionCode，与本次运行的不符即重解
 * 解包只由 [PiInstallCoordinator] 发起；取路径的地方一律用 [installedDir]，别在读一个文件时
 * 顺带搬几十 MB
 *
 * agent 以 PI 根为工作目录，记录与日志就写在这棵目录里（`debug/`、`config/` 之类）。升级重解
 * 只替换包带来的根级条目，其余原样留着——与 MXU 全量更新同一口径，桌面端的 agent 本来就是
 * 按「这些文件跨版本还在」写的
 */
class PiInstaller(
    private val pkg: PiPackage,
    private val versionCode: Int,
    /** 配方的 `logs.include`，相对 PI 根的 glob；升级时按它清掉过期的 agent 日志 */
    private val logInclude: List<String> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 已解包的 PI 根目录，本身不解包
     * 就绪定义与 [ensureInstalled] 相同：目录在、标记与本次 versionCode 一致
     */
    fun installedDir(): File {
        val target = File(AppPaths.ROOT, AppFiles.PI_DIR)
        if (!isCurrentInstall(AppPaths.ROOT, target)) {
            throw PiNotInstalledException(target)
        }
        return target
    }

    /**
     * 标记与本次运行的 versionCode 一致就直接返回，否则重解；包外的根级条目留着
     * 阻塞 IO：调用方须在 IO 线程
     */
    @Synchronized
    fun ensureInstalled(onProgress: PiUnpackProgress = NO_PROGRESS): File {
        val base = AppPaths.ROOT
        val target = File(base, AppFiles.PI_DIR)
        if (isCurrentInstall(base, target)) return target
        return install(base, onProgress, wipe = false)
    }

    /**
     * 不看标记，无条件重解；设置页的手动重来与失败重试都走这条
     *
     * 眼下这份是完整的才全清：那是用户点「重新解压」要的恢复原样，agent 写坏的记录也靠它清。
     * 标记对不上说明上一次重解没走完，这时的重试是那次升级的延续，agent 数据照样留着
     */
    @Synchronized
    fun reinstall(onProgress: PiUnpackProgress = NO_PROGRESS): File {
        val base = AppPaths.ROOT
        return install(base, onProgress, wipe = isCurrentInstall(base, File(base, AppFiles.PI_DIR)))
    }

    private fun isCurrentInstall(base: File, target: File): Boolean {
        val marker = File(base, PI_MARKER_NAME)
        return target.isDirectory && marker.isFile && marker.readText().trim() == versionCode.toString()
    }

    private fun install(base: File, onProgress: PiUnpackProgress, wipe: Boolean): File {
        val target = File(base, AppFiles.PI_DIR)
        val marker = File(base, PI_MARKER_NAME)
        val rootsFile = File(base, PI_ROOTS_NAME)

        // 顺序不能换：标记先失效，解包中途掉电时残留内容不会被当成完整的一份
        marker.delete()
        // 换标记之前的包留在外部私有目录里的，不删会一直躺着，adb 翻这个目录时看着像还在生效
        File(base, LEGACY_MARKER_NAME).delete()
        if (wipe) {
            target.deleteRecursively()
        } else {
            // 上一份包带过、这一份不再带的根级条目，不清就一直躺着；新包自己带的由 unpack 逐个替换
            // 没有这份清单的旧安装只能靠后者，那一次升级可能留下旧包独有的目录
            readRoots(rootsFile).forEach { File(target, it).deleteRecursively() }
            pruneStaleLogs(target)
        }
        target.mkdirs()
        val roots = unpack(target, onProgress)
        ensureNoMedia(base)
        rootsFile.writeText(roots.joinToString("\n"))
        marker.writeText(versionCode.toString())
        return target
    }

    /** 清单是外部私有目录里的普通文件，谁都改得动：混进路径分隔符或 `..` 的行不认，免得删到 PI 根外面 */
    private fun readRoots(rootsFile: File): List<String> {
        if (!rootsFile.isFile) return emptyList()
        return rootsFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
    }

    /**
     * agent 的日志不随升级清空之后，得有别的东西管住它的体积
     * 口径跟导出一致：[LogExportCollector] 只收近 [LogExportCollector.ROLLING_KEEP_DAYS] 天的，
     * 再早的留着也没人看得到。只动配方点名的日志，记录文件不碰
     */
    private fun pruneStaleLogs(root: File) {
        if (logInclude.isEmpty() || !root.isDirectory) return
        val patterns = logInclude.map(LogExportCollector::globToRegex)
        val cutoff = now() - TimeUnit.DAYS.toMillis(LogExportCollector.ROLLING_KEEP_DAYS.toLong())
        root.walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoff }
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                patterns.any { it.matches(relative) }
            }
            .toList()
            .forEach { it.delete() }
    }

    /** 返回这份包带来的根级条目名；每个在写入前先整体删掉，旧版本才有的文件不残留 */
    private fun unpack(dest: File, onProgress: PiUnpackProgress): Set<String> {
        val archive = pkg.openArchive()
        if (archive != null) {
            return ZipInputStream(archive).use { zip -> unpackZip(dest, zip, onProgress) }
        }

        val entries = pkg.manifest()
        if (entries.isEmpty()) return emptySet()
        val roots = entries.mapTo(linkedSetOf(), ::rootOf)
        roots.forEach { File(dest, it).deleteRecursively() }
        entries.mapNotNullTo(mutableSetOf()) { File(dest, it).parentFile }.forEach { it.mkdirs() }
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val done = AtomicInteger()
        for (entry in entries) {
            pkg.open(entry).use { input ->
                File(dest, entry).outputStream().use { output ->
                    input.copyTo(output, buffer)
                }
            }
            onProgress(done.incrementAndGet(), entries.size, entry)
        }
        requireInterfaceJson(dest)
        return roots
    }

    private fun unpackZip(dest: File, zip: ZipInputStream, onProgress: PiUnpackProgress): Set<String> {
        val destCanon = dest.canonicalFile
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val roots = linkedSetOf<String>()
        var done = 0
        while (true) {
            val zipEntry = zip.nextEntry ?: break
            if (zipEntry.isDirectory) {
                zip.closeEntry()
                continue
            }
            val name = normalizeZipName(zipEntry.name)
            val outFile = File(dest, name)
            val outCanon = outFile.canonicalFile
            if (outCanon != destCanon && !outCanon.path.startsWith(destCanon.path + File.separator)) {
                throw PiUnpackException("illegal PI archive entry: $name")
            }
            // 归档只扫一遍，根级条目名事先拿不到：头一回遇到时把旧的那棵整体删掉
            val root = rootOf(name)
            if (roots.add(root)) File(dest, root).deleteRecursively()
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { output -> zip.copyTo(output, buffer) }
            zip.closeEntry()
            done++
            onProgress(done, 0, name)
        }
        if (done > 0) requireInterfaceJson(dest)
        return roots
    }

    private fun rootOf(entry: String): String = entry.substringBefore('/')

    private fun requireInterfaceJson(dest: File) {
        if (!File(dest, INTERFACE_JSON).isFile) {
            throw PiUnpackException("PI archive is missing $INTERFACE_JSON")
        }
    }

    private fun normalizeZipName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.split('/').any { it == ".." }) {
            throw PiUnpackException("illegal PI archive entry: $raw")
        }
        return name
    }

    /** 外部私有目录会被媒体扫描，PI 里的 PNG 会整片进相册 */
    private fun ensureNoMedia(base: File) {
        val noMedia = File(base, NO_MEDIA_NAME)
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
        }
    }

    private fun InputStream.copyTo(out: OutputStream, buffer: ByteArray) {
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
    }

    companion object {
        /** 提交标记：解包全部成功后才写，内容是出这个包时的 versionCode */
        const val PI_MARKER_NAME = "pi.version"

        /** 上一次解包带来的根级条目名，一行一个；下次重解靠它认出哪些是包的、哪些是 agent 写的 */
        const val PI_ROOTS_NAME = "pi.roots"

        /** 按内容指纹判过期的旧包留下的标记 */
        private const val LEGACY_MARKER_NAME = "pi.fingerprint"

        private const val INTERFACE_JSON = "interface.json"
        private const val NO_MEDIA_NAME = ".nomedia"
        private const val COPY_BUFFER_BYTES = 128 * 1024

        private val NO_PROGRESS: PiUnpackProgress = { _, _, _ -> }
    }
}

/** 构建期 packPiArchive 落在 assets 根的 PI 归档；散装 assets 会被 AAPT 改写 */
internal const val PI_ARCHIVE_ASSET = "pi.zip"
