package com.aliothmoon.maafw.project

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 归档版内存 PI 包，解包走 [openArchive]
 * [archiveCrc] 冒充 APK 中央目录里记的那份；[bytes] 可以是故意改坏的归档
 */
class ZipPiPackage(
    private val bytes: ByteArray,
    private val manifest: List<String> = emptyList(),
    private val archiveCrc: Long? = null,
) : PiPackage {

    constructor(files: Map<String, String>, manifest: List<String> = files.keys.sorted()) :
        this(zipBytes(files), manifest)

    override fun manifest(): List<String> = manifest

    override fun open(path: String): InputStream = error(path)

    override fun openArchive(): InputStream = bytes.inputStream()

    override fun archiveCrc(): Long? = archiveCrc
}

fun zipBytes(files: Map<String, String>): ByteArray =
    ByteArrayOutputStream().use { raw ->
        ZipOutputStream(raw).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        raw.toByteArray()
    }

fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

/** 第一个条目压缩数据中段抹成 0：多线程下载器某一段没写进去，文件大小不变 */
fun zeroFilledArchive(files: Map<String, String>): Pair<ByteArray, ByteArray> {
    val intact = zipBytes(files)
    val damaged = intact.copyOf()
    // 本地文件头 30 字节 + 文件名，ZipOutputStream 不写 extra
    val dataStart = 30 + files.keys.first().toByteArray().size
    damaged.fill(0, dataStart + 64, dataStart + 64 + 256)
    return intact to damaged
}

/** 压缩后还剩几 KB 的正文，抹掉的那段才落得进 deflate 数据里 */
fun bulkyText(): String = (0 until 4000).joinToString(",") { (it * 7919 % 10007).toString() }
