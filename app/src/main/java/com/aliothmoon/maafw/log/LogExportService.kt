package com.aliothmoon.maafw.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CancellationException
import com.aliothmoon.maafw.domain.SECRET_MASK
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
    /** 调试模式下额外附一份 `getprop`：ROM 差异是排障时最先要问的 */
    private val debugMode: () -> Boolean,
    /** 设备快照文本；采集在 [DeviceInfoCollector] */
    private val deviceInfo: () -> String,
    /** 脱敏后的设置快照文本；采集在 [ExportSnapshots] */
    private val settingsSnapshot: suspend () -> String,
    /** 脱敏后的 PI 运行配置快照文本；采集在 [ExportSnapshots] */
    private val piConfigSnapshot: suspend () -> String,
    /**
     * 当前保存着的 PI password 明文，打包时在文本日志里换成掩码。
     * MaaFramework 的 `MaaTaskerPostTask` 会把替换后的整份 pipeline_override 写进框架日志 `log/maafw.log`，外壳拦不住，只能在导出这一步补
     */
    private val secrets: suspend () -> Collection<String> = { emptyList() },
) {

    /** 返回 null = 打包失败；没有日志时也保留设备信息快照 */
    suspend fun exportZip(): File? = withContext(MaaDispatchers.IO) {
        val files = LogExportCollector.collect(roots(), System.currentTimeMillis())
        if (files.isEmpty()) {
            Timber.w("no log files to export, packing device info only")
        }
        val secrets = if (debugMode()) emptyList() else redactable(secrets())
        try {
            val settingsSnapshot = settingsSnapshot()
            val piConfigSnapshot = piConfigSnapshot()
            val dir = File(baseDir(), "${LOG_DIR_NAME}/${LogExportCollector.EXPORT_DIR_NAME}")
                .apply { mkdirs() }
            // 只留最新一份：旧包对用户没用，留着纯占空间
            dir.listFiles()?.forEach { it.delete() }
            val zip = File(dir, "maafw_logs_${STAMP.format(Date())}.zip")
            writeZip(zip, files, secrets, settingsSnapshot, piConfigSnapshot)
            zip
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "export logs failed")
            null
        }
    }

    suspend fun shareIntent(): Intent? = exportZip()?.let(::createShareIntent)

    /** 写进用户经 SAF 选的位置；成功返回显示名 */
    suspend fun exportTo(target: Uri): String? = withContext(MaaDispatchers.IO) {
        val zip = exportZip() ?: return@withContext null
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null
            displayName(target) ?: zip.name
        }.onFailure { Timber.w(it, "write to export target failed: %s", target) }.getOrNull()
    }

    fun suggestedFileName(): String = "maafw_logs_${STAMP.format(Date())}.zip"

    private fun writeZip(
        zip: File,
        files: List<File>,
        secrets: List<String>,
        settingsSnapshot: String,
        piConfigSnapshot: String,
    ) {
        val base = baseDir()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            if (debugMode()) appendDeviceProperties(out)
            appendDeviceInfo(out)
            appendSnapshot(out, SETTINGS_SNAPSHOT_ENTRY, settingsSnapshot)
            appendSnapshot(out, PI_CONFIG_SNAPSHOT_ENTRY, piConfigSnapshot)
            val skipped = mutableListOf<String>()
            files.forEach { file ->
                appendLogFile(out, file, base, secrets, skipped)
            }
            if (skipped.isNotEmpty()) {
                out.putNextEntry(ZipEntry(SKIPPED_ENTRY))
                out.write(skipped.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    /** 提权进程写的文件可能对 App 不可读，逐个跳过，不拖垮整包 */
    private fun appendLogFile(
        out: ZipOutputStream,
        file: File,
        base: File,
        secrets: List<String>,
        skipped: MutableList<String>,
    ) {
        val name = file.relativeTo(base).invariantSeparatorsPath
        try {
            file.inputStream().use { input ->
                val entry = ZipEntry(name).apply { time = file.lastModified() }
                out.putNextEntry(entry)
                if (secrets.isNotEmpty() && file.extension.lowercase() in TEXT_EXTENSIONS) {
                    copyRedacted(input, out, secrets)
                } else {
                    input.copyTo(out, BUFFER_SIZE)
                }
                out.closeEntry()
            }
        } catch (e: IOException) {
            Timber.w(e, "Skip unreadable log file: %s", name)
            skipped += "$name: ${e.message ?: e::class.java.simpleName}"
            runCatching { out.closeEntry() }
        }
    }

    /** 逐行替换，大文件不整份读进内存，换行统一成 LF；writer 只 flush 不 close，close 会把整个 zip 流关掉 */
    private fun copyRedacted(input: InputStream, out: ZipOutputStream, secrets: List<String>) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            writer.write(secrets.fold(line) { text, secret -> text.replace(secret, SECRET_MASK) })
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

    private fun appendSnapshot(
        out: ZipOutputStream,
        entryName: String,
        snapshot: String,
    ) {
        out.putNextEntry(ZipEntry(entryName))
        out.write(snapshot.toByteArray(Charsets.UTF_8))
        out.closeEntry()
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

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val LOG_DIR_NAME = "log"
        const val PROPERTIES_ENTRY = "properties.txt"
        const val DEVICE_INFO_ENTRY = "device_info.txt"
        const val SETTINGS_SNAPSHOT_ENTRY = "settings_snapshot.json"
        const val PI_CONFIG_SNAPSHOT_ENTRY = "pi_config_snapshot.json"
        const val SKIPPED_ENTRY = "export_skipped.txt"
        const val MIME_ZIP = "application/zip"
        const val BUFFER_SIZE = 8 * 1024

        /** 一两个字符的串在日志里到处都是，替换掉会把整份日志毁了；更短的密码不打码 */
        const val MIN_REDACT_LENGTH = 4
        val TEXT_EXTENSIONS = setOf("log", "txt", "json", "jsonl")

        /** 长的先换：一个密码是另一个的子串时，先换短的会留下长的那截尾巴 */
        fun redactable(secrets: Collection<String>): List<String> =
            secrets.filter { it.length >= MIN_REDACT_LENGTH }.distinct().sortedByDescending { it.length }

        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
