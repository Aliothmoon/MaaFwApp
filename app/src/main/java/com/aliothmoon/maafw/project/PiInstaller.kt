package com.aliothmoon.maafw.project

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 打包进 APK 的 PI 只读包
 * 不复用 ProjectSource：解包要按字节搬运图片与模型，read(String) 的文本语义不够用
 */
interface PiPackage {
    /** 构建期 writePiManifest 产出的解包清单，元素是相对 PI 根的文件路径 */
    fun manifest(): List<String>

    fun open(path: String): InputStream
}

/** 路径一律相对 PI 根，与 [ProjectSource] 保持同一套相对路径语义 */
class AssetPiPackage(context: Context, private val root: String) : PiPackage {

    private val assets = context.applicationContext.assets

    override fun manifest(): List<String> =
        assets.open(PI_MANIFEST_ASSET).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }

    override fun open(path: String): InputStream = assets.open("$root/$path")
}

/**
 * 逐条目的解包进度；done 从 1 起计，total 是清单条目数
 * 由解包线程池的多个线程并发调用，实现要自己扛并发
 */
typealias PiUnpackProgress = (done: Int, total: Int, path: String) -> Unit

/**
 * 把打包的 PI 解包到应用外部私有目录
 * native MaaFramework 只认文件系统路径，而 APK 内的 assets 条目不是文件；落点不能用 filesDir——
 * 特权进程是 shell 身份，进不去 0700 的 app 私有目录（docs/privileged-runtime.md §9）
 *
 * 标记文件记 versionCode，与本次运行的不符即整体重解
 * 解包只由 [PiInstallCoordinator] 发起；取路径的地方一律用 [installedDir]，别在读一个文件时
 * 顺带搬几十 MB
 */
class PiInstaller(
    private val pkg: PiPackage,
    /** 外部私有目录不可用时抛出，交由调用方记成可读诊断，不在此处静默回落 */
    private val baseDir: () -> File,
    private val versionCode: Int,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
) {

    /**
     * 已解包的 PI 根目录，本身不解包
     * 尚未解包时抛出——那说明调用顺序错了，不该在这里补一次几十 MB 的搬运
     */
    fun installedDir(): File {
        val target = File(baseDir(), PI_DIR_NAME)
        check(target.isDirectory) { "PI 尚未解包：${target.absolutePath}" }
        return target
    }

    /**
     * 标记与本次运行的 versionCode 一致就直接返回，否则整体重解
     * 阻塞 IO：调用方须在 IO 线程
     */
    @Synchronized
    fun ensureInstalled(onProgress: PiUnpackProgress = NO_PROGRESS): File {
        val base = baseDir()
        val target = File(base, PI_DIR_NAME)
        val marker = File(base, PI_MARKER_NAME)
        if (target.isDirectory && marker.isFile && marker.readText().trim() == versionCode.toString()) {
            return target
        }
        return install(base, onProgress)
    }

    /** 不看标记，无条件重解；设置页的手动重来与失败重试都走这条 */
    @Synchronized
    fun reinstall(onProgress: PiUnpackProgress = NO_PROGRESS): File = install(baseDir(), onProgress)

    private fun install(base: File, onProgress: PiUnpackProgress): File {
        val target = File(base, PI_DIR_NAME)
        val marker = File(base, PI_MARKER_NAME)

        // 顺序不能换：标记先失效，解包中途掉电时残留内容不会被当成完整的一份
        marker.delete()
        // 换标记之前的包留在外部私有目录里的，不删会一直躺着，adb 翻这个目录时看着像还在生效
        File(base, LEGACY_MARKER_NAME).delete()
        target.deleteRecursively()
        target.mkdirs()
        unpack(target, onProgress)
        ensureNoMedia(base)
        marker.writeText(versionCode.toString())
        return target
    }

    private fun unpack(dest: File, onProgress: PiUnpackProgress) {
        val entries = pkg.manifest()
        if (entries.isEmpty()) return

        // 先建目录再并发写文件：mkdirs 并发调用会有一方返回 false，单线程建好省掉这层不确定
        entries.mapNotNullTo(mutableSetOf()) { File(dest, it).parentFile }.forEach { it.mkdirs() }

        val pool = Executors.newFixedThreadPool(parallelism)
        // 每线程一块大 buffer：默认 8 KB 拷几十 MB 会被 read/write 次数拖死
        val buffers = ThreadLocal.withInitial { ByteArray(COPY_BUFFER_BYTES) }
        val done = AtomicInteger()
        try {
            val tasks = entries.map { entry ->
                Callable {
                    pkg.open(entry).use { input ->
                        File(dest, entry).outputStream().use { output ->
                            input.copyTo(output, buffers.get())
                        }
                    }
                    onProgress(done.incrementAndGet(), entries.size, entry)
                }
            }
            // 任一条目失败即整体失败：解包不完整比解包失败更难查
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
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
        /** 解包内容的固定目录名；路径稳定，版本靠标记文件而非目录名区分 */
        const val PI_DIR_NAME = "pi"

        /** 提交标记：解包全部成功后才写，内容是出这个包时的 versionCode */
        const val PI_MARKER_NAME = "pi.version"

        /** 按内容指纹判过期的旧包留下的标记 */
        private const val LEGACY_MARKER_NAME = "pi.fingerprint"

        private const val NO_MEDIA_NAME = ".nomedia"
        private const val COPY_BUFFER_BYTES = 128 * 1024

        private val NO_PROGRESS: PiUnpackProgress = { _, _, _ -> }
    }
}

/** 构建期 writePiManifest 落在 assets 根的解包清单 */
private const val PI_MANIFEST_ASSET = "pi.manifest"
