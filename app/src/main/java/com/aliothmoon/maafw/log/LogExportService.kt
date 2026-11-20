package com.aliothmoon.maafw.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.util.displayName
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把日志打成 zip 交出去
 *
 * 反馈问题时最费劲的一步是「把日志弄出来」——设备上没有文件管理入口，adb 又不是人人都有。
 * 打一个包直接分享出去，这一步就没了
 *
 * 不删源：导完还留在设备上，用户可以再导一次
 */
class LogExportService(
    private val context: Context,
    /** 要收的几棵目录；zip 里的路径相对 [baseDir] */
    private val baseDir: () -> File,
    private val roots: () -> List<File>,
    /** agent 自己写在 PI 根下的日志 */
    private val piLogs: () -> LogExportCollector.PiLogs? = { null },
    /** 调试模式下额外附一份 `getprop`：ROM 差异是排障时最先要问的 */
    private val debugMode: () -> Boolean,
    /** 设备快照文本；采集在 [DeviceInfoCollector] */
    private val deviceInfo: () -> String,
    /** 当前保存着的 PI password 明文，打包时在文本日志里换成掩码，见 [SecretRedaction] */
    private val secrets: suspend () -> Collection<String> = { emptyList() },
    /** 压缩后的包体积预算 */
    private val maxZipBytes: Long = MAX_ZIP_BYTES,
) {

    /** 返回 null = 打包失败；没有日志时也保留设备信息快照 */
    suspend fun exportZip(): File? = withContext(MaaDispatchers.IO) {
        val files = LogExportCollector.collect(roots(), System.currentTimeMillis(), piLogs())
        if (files.isEmpty()) {
            Timber.w("no log files to export, packing device info only")
        }
        runCatching {
            val dir = File(baseDir(), "${LOG_DIR_NAME}/${LogExportCollector.EXPORT_DIR_NAME}")
                .apply { mkdirs() }
            // 只留最新一份：旧包对用户没用，留着纯占空间
            dir.listFiles()?.forEach { it.delete() }
            val zip = File(dir, "maafw_logs_${STAMP.format(Date())}.zip")
            writeZip(zip, files, SecretRedaction.redactable(secrets()))
            zip
        }.onFailure { Timber.w(it, "export logs failed") }.getOrNull()
    }

    suspend fun shareIntent(): Intent? = exportZip()?.let(::createShareIntent)

    /** 写进用户经 SAF 选的位置；成功返回显示名 */
    suspend fun exportTo(target: Uri): String? = withContext(MaaDispatchers.IO) {
        val zip = exportZip() ?: return@withContext null
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null
            context.contentResolver.displayName(target) ?: zip.name
        }.onFailure { Timber.w(it, "write to export target failed: %s", target) }.getOrNull()
    }

    fun suggestedFileName(): String = "maafw_logs_${STAMP.format(Date())}.zip"

    private fun writeZip(zip: File, files: List<File>, secrets: List<String>) {
        val base = baseDir()
        val plan = LogExportCollector.plan(files)
        // 量的是压缩后的字节
        val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(zip)))
        ZipOutputStream(counter).use { out ->
            if (debugMode()) appendDeviceProperties(out)
            appendDeviceInfo(out)
            val skipped = mutableListOf<String>()
            val ratio = CompressionRatio()
            fun append(file: File) {
                // 正写着的 maafw.log 拷完会变长，原始大小取拷之前的
                val length = file.length()
                val before = counter.count
                if (appendLogFile(out, file, base, secrets, skipped) && isText(file)) {
                    ratio.record(length, counter.count - before)
                }
            }
            plan.required.forEach(::append)
            // 写进去就退不出来，只能先估：文本按已写的压缩比，截图压不动按原大小
            val dropped = mutableListOf<File>()
            for (file in plan.optional) {
                val estimate = if (isText(file)) ratio.estimate(file.length()) else file.length()
                if (counter.count + estimate > maxZipBytes) dropped += file else append(file)
            }
            if (dropped.isNotEmpty()) {
                Timber.i("export size budget reached, dropped %d older files", dropped.size)
                dropped.forEach { skipped += "${it.relativeTo(base).invariantSeparatorsPath}: $OVER_BUDGET" }
            }
            if (skipped.isNotEmpty()) {
                out.putNextEntry(ZipEntry(SKIPPED_ENTRY))
                out.write(skipped.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    /** 提权进程写的文件可能对 App 不可读，逐个跳过，不拖垮整包；返回是否写进去了 */
    private fun appendLogFile(
        out: ZipOutputStream,
        file: File,
        base: File,
        secrets: List<String>,
        skipped: MutableList<String>,
    ): Boolean {
        val name = file.relativeTo(base).invariantSeparatorsPath
        return try {
            file.inputStream().use { input ->
                val entry = ZipEntry(name).apply { time = file.lastModified() }
                out.putNextEntry(entry)
                if (secrets.isNotEmpty() && isText(file)) {
                    copyRedacted(input, out, secrets)
                } else {
                    input.copyTo(out, BUFFER_SIZE)
                }
                out.closeEntry()
            }
            true
        } catch (e: IOException) {
            Timber.w(e, "Skip unreadable log file: %s", name)
            skipped += "$name: ${e.message ?: e::class.java.simpleName}"
            runCatching { out.closeEntry() }
            false
        }
    }

    private fun isText(file: File): Boolean = file.extension.lowercase() in TEXT_EXTENSIONS

    /** 逐行替换，大文件不整份读进内存，换行统一成 LF；writer 只 flush 不 close，close 会把整个 zip 流关掉 */
    private fun copyRedacted(input: InputStream, out: ZipOutputStream, secrets: List<String>) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            writer.write(SecretRedaction.redact(line, secrets))
            writer.write("\n")
        }
        writer.flush()
    }

    /** 取不到就跳过：少一份设备属性不该让整个导出失败 */
    private fun appendDeviceProperties(out: ZipOutputStream) {
        runCatching {
            val process = Runtime.getRuntime().exec("getprop")
            out.putNextEntry(ZipEntry(PROPERTIES_ENTRY))
            process.inputStream.use { it.copyTo(out, BUFFER_SIZE) }
            out.closeEntry()
            process.waitFor()
        }.onFailure { Timber.w(it, "collect device properties failed") }
    }

    private fun appendDeviceInfo(out: ZipOutputStream) {
        runCatching {
            out.putNextEntry(ZipEntry(DEVICE_INFO_ENTRY))
            out.write(deviceInfo().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }.onFailure { Timber.w(it, "collect device info failed") }
    }

    /** 走 FileProvider 而非 file://：API 24 起后者直接抛 FileUriExposedException */
    private fun createShareIntent(zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(com.aliothmoon.maafw.R.string.log_export_subject))
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    /** 小文件的条目头会把比例带偏，只记大的 */
    private class CompressionRatio {
        private var raw = 0L
        private var packed = 0L

        fun record(rawBytes: Long, packedBytes: Long) {
            if (rawBytes < MIN_RATIO_SAMPLE_BYTES) return
            raw += rawBytes
            packed += packedBytes
        }

        fun estimate(rawBytes: Long): Long {
            val ratio = if (raw > 0) packed.toDouble() / raw else DEFAULT_TEXT_RATIO
            return (rawBytes * ratio * RATIO_MARGIN).toLong()
        }
    }

    private companion object {
        /** GitHub issue 附件上限 25MB，留一点给 zip 的中央目录 */
        const val MAX_ZIP_BYTES = 24L * 1024 * 1024
        const val OVER_BUDGET = "over export size budget"
        const val MIN_RATIO_SAMPLE_BYTES = 64L * 1024

        /** 还没写过大文本日志时的保守估计；maafw.log 实测压到 5% 上下 */
        const val DEFAULT_TEXT_RATIO = 0.2
        const val RATIO_MARGIN = 1.25

        const val LOG_DIR_NAME = "log"
        const val PROPERTIES_ENTRY = "properties.txt"
        const val DEVICE_INFO_ENTRY = "device_info.txt"
        const val SKIPPED_ENTRY = "export_skipped.txt"
        const val MIME_ZIP = "application/zip"
        const val BUFFER_SIZE = 8 * 1024
        val TEXT_EXTENSIONS = setOf("log", "txt", "json", "jsonl")

        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
