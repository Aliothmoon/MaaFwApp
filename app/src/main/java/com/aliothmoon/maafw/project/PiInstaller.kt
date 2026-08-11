package com.aliothmoon.maafw.project

import android.content.Context
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 打包进 APK 的 PI 只读包
 * 不复用 ProjectSource：解包要按字节搬运图片与模型，read(String) 的文本语义不够用
 */
interface PiPackage {
    /** 构建期 writePiIndex 产出的解包清单，元素是相对 PI 根的文件路径 */
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
 * 把打包的 PI 解包到应用外部私有目录
 * native MaaFramework 只认文件系统路径，而 APK 内的 assets 条目不是文件；落点不能用 filesDir——
 * 特权进程是 shell 身份，进不去 0700 的 app 私有目录（docs/privileged-runtime.md §9）
 *
 * 指纹由构建期算出并随 assets 分发，与标记文件不符即整体重解
 */
class PiInstaller(
    private val pkg: PiPackage,
    private val fingerprint: String,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
) {

    /**
     * 返回可直接交给 native 的 PI 根目录，必要时先解包
     * 阻塞 IO：调用方须在 IO 线程（ProjectRepository 的 reload 已经在 IO dispatcher 上）
     */
    @Synchronized
    fun ensureInstalled(): File {
        val base = AppPaths.externalRoot
        val target = File(base, AppFiles.PI_DIR)
        val marker = File(base, PI_MARKER_NAME)
        if (target.isDirectory && marker.isFile && marker.readText().trim() == fingerprint) {
            return target
        }

        // 顺序不能换：标记先失效，解包中途掉电时残留内容不会被当成完整的一份
        marker.delete()
        target.deleteRecursively()
        target.mkdirs()
        unpack(target)
        ensureNoMedia(base)
        marker.writeText(fingerprint)
        return target
    }

    private fun unpack(dest: File) {
        val entries = pkg.manifest()
        if (entries.isEmpty()) return

        // 先建目录再并发写文件：mkdirs 并发调用会有一方返回 false，单线程建好省掉这层不确定
        entries.mapNotNullTo(mutableSetOf()) { File(dest, it).parentFile }.forEach { it.mkdirs() }

        val pool = Executors.newFixedThreadPool(parallelism)
        // 每线程一块大 buffer：默认 8 KB 拷几十 MB 会被 read/write 次数拖死
        val buffers = ThreadLocal.withInitial { ByteArray(COPY_BUFFER_BYTES) }
        try {
            val tasks = entries.map { entry ->
                Callable {
                    pkg.open(entry).use { input ->
                        File(dest, entry).outputStream().use { output ->
                            input.copyTo(output, buffers.get())
                        }
                    }
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
        /** 提交标记：解包全部成功后才写，内容是构建期指纹 */
        const val PI_MARKER_NAME = "pi.fingerprint"

        private const val NO_MEDIA_NAME = ".nomedia"
        private const val COPY_BUFFER_BYTES = 128 * 1024
    }
}

/** 构建期 writePiIndex 落在 assets 根的两个索引文件 */
private const val PI_FINGERPRINT_ASSET = "pi.fingerprint"
private const val PI_MANIFEST_ASSET = "pi.manifest"

/** 缺指纹说明这个包没经过 syncPiAssets；回落固定值即「永不过期」，不影响已解包的内容可用 */
private const val UNKNOWN_FINGERPRINT = "unknown"

fun readPiFingerprint(context: Context): String = runCatching {
    context.applicationContext.assets
        .open(PI_FINGERPRINT_ASSET)
        .bufferedReader()
        .use { it.readText() }
        .trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_FINGERPRINT
