package com.aliothmoon.maafw.project

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * 打包进 APK 的 PI 只读包
 * 不复用 ProjectSource：解包要按字节搬运图片与模型，read(String) 的文本语义不够用
 */
interface PiPackage {
    /** 目录的直接子项；路径不是目录时返回空 */
    fun list(path: String): List<String>

    fun open(path: String): InputStream
}

/** 路径一律相对 PI 根，与 [ProjectSource] 保持同一套相对路径语义 */
class AssetPiPackage(context: Context, private val root: String) : PiPackage {

    private val assets = context.applicationContext.assets

    override fun list(path: String): List<String> =
        assets.list(assetPath(path))?.toList().orEmpty()

    override fun open(path: String): InputStream = assets.open(assetPath(path))

    private fun assetPath(path: String): String = if (path.isEmpty()) root else "$root/$path"
}

/**
 * 把打包的 PI 解包到应用私有目录
 * native MaaFramework 只认文件系统路径，而 APK 内的 assets 条目不是文件，拿不到路径
 *
 * 指纹由构建期的 writePiFingerprint 算出；变化即重解包，旧版本目录一并清理
 */
class PiInstaller(
    private val pkg: PiPackage,
    private val baseDir: File,
    private val fingerprint: String,
) {

    /**
     * 返回可直接交给 native 的 PI 根目录，必要时先解包
     * 阻塞 IO：调用方须在 IO 线程（ProjectRepository 的 reload 已经在 IO dispatcher 上）
     */
    @Synchronized
    fun ensureInstalled(): File {
        val target = File(baseDir, fingerprint)
        if (target.isDirectory) return target

        // 先落到暂存目录再整体改名：改名是原子的，目录存在即代表内容完整，
        // 解包中途掉电只会留下暂存目录，下次重来
        val staging = File(baseDir, STAGING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        unpack("", staging)

        baseDir.listFiles()?.forEach { if (it != staging) it.deleteRecursively() }
        check(staging.renameTo(target)) { "PI 解包落位失败: $target" }
        return target
    }

    private fun unpack(path: String, dest: File) {
        val children = pkg.list(path)
        if (children.isEmpty()) {
            // assets 不区分文件与空目录：打得开就是文件，打不开按空目录跳过
            val stream = try {
                pkg.open(path)
            } catch (e: Exception) {
                return
            }
            dest.parentFile?.mkdirs()
            stream.use { input -> dest.outputStream().use(input::copyTo) }
            return
        }
        dest.mkdirs()
        children.forEach { child ->
            unpack(if (path.isEmpty()) child else "$path/$child", File(dest, child))
        }
    }

    companion object {
        private const val STAGING_DIR = ".staging"
    }
}

/** 构建期 writePiFingerprint 落在 assets 根的指纹文件 */
private const val PI_FINGERPRINT_ASSET = "pi.fingerprint"

/** 缺指纹说明这个包没经过 syncPiAssets；回落固定值即「永不过期」，不影响已解包的内容可用 */
private const val UNKNOWN_FINGERPRINT = "unknown"

fun readPiFingerprint(context: Context): String = runCatching {
    context.applicationContext.assets
        .open(PI_FINGERPRINT_ASSET)
        .bufferedReader()
        .use { it.readText() }
        .trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_FINGERPRINT
