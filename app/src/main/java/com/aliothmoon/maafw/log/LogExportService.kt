package com.aliothmoon.maafw.log

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.BuildConfig
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
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
    private val deviceInfoProvider: (() -> String)? = null,
) {

    private val deviceInfo: () -> String = {
        deviceInfoProvider?.invoke() ?: DeviceInfoText.render(collectDeviceInfo())
    }

    /** 返回 null = 打包失败；没有日志时也保留设备信息快照 */
    suspend fun exportZip(): File? = withContext(MaaDispatchers.IO) {
        val files = LogExportCollector.collect(roots(), System.currentTimeMillis())
        if (files.isEmpty()) {
            Timber.w("没有可导出的日志，仅导出设备信息")
        }
        runCatching {
            val dir = File(baseDir(), "${LOG_DIR_NAME}/${LogExportCollector.EXPORT_DIR_NAME}")
                .apply { mkdirs() }
            // 只留最新一份：旧包对用户没用，留着纯占空间
            dir.listFiles()?.forEach { it.delete() }
            val zip = File(dir, "maafw_logs_${STAMP.format(Date())}.zip")
            writeZip(zip, files)
            zip
        }.onFailure { Timber.w(it, "导出日志失败") }.getOrNull()
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
        }.onFailure { Timber.w(it, "写入导出目标失败：%s", target) }.getOrNull()
    }

    fun suggestedFileName(): String = "maafw_logs_${STAMP.format(Date())}.zip"

    private fun writeZip(zip: File, files: List<File>) {
        val base = baseDir()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            if (debugMode()) appendDeviceProperties(out)
            appendDeviceInfo(out)
            files.forEach { file ->
                val entry = ZipEntry(file.relativeTo(base).invariantSeparatorsPath)
                entry.time = file.lastModified()
                out.putNextEntry(entry)
                file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
                out.closeEntry()
            }
        }
    }

    /** 取不到就跳过：少一份设备属性不该让整个导出失败 */
    private fun appendDeviceProperties(out: ZipOutputStream) {
        runCatching {
            val process = Runtime.getRuntime().exec("getprop")
            out.putNextEntry(ZipEntry(PROPERTIES_ENTRY))
            process.inputStream.use { it.copyTo(out, BUFFER_SIZE) }
            out.closeEntry()
            process.waitFor()
        }.onFailure { Timber.w(it, "收集设备属性失败") }
    }

    private fun appendDeviceInfo(out: ZipOutputStream) {
        runCatching {
            out.putNextEntry(ZipEntry(DEVICE_INFO_ENTRY))
            out.write(deviceInfo().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }.onFailure { Timber.w(it, "收集设备信息失败") }
    }

    private fun collectDeviceInfo() = DeviceInfo(
        exportTime = ZonedDateTime.now(),
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
        gitCommit = BuildConfig.MAFW_GIT_COMMIT,
        gitTag = BuildConfig.MAFW_GIT_TAG,
        parentGitCommit = BuildConfig.MAFW_PARENT_GIT_COMMIT,
        parentGitTag = BuildConfig.MAFW_PARENT_GIT_TAG,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        securityPatch = Build.VERSION.SECURITY_PATCH,
        abi = Build.SUPPORTED_ABIS.joinToString(),
        screen = screenInfo,
        memory = memoryInfo,
        storage = storageInfo,
        batteryOptimization = batteryOptimized,
        selinux = selinuxMode,
    )

    private val screenInfo: String
        get() = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                @Suppress("DEPRECATION")
                val size = Point()
                wm.defaultDisplay.getRealSize(size)
                size.x to size.y
            }
            @Suppress("DEPRECATION")
            val refresh = wm.defaultDisplay.refreshRate
            "$w x $h @ ${"%.0f".format(Locale.US, refresh)}Hz " +
                "(density ${context.resources.displayMetrics.densityDpi}dpi)"
        }.getOrDefault("unknown")

    private val memoryInfo: String
        get() = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${formatGb(mi.totalMem)} total, ${formatGb(mi.availMem)} free"
        }.getOrDefault("unknown")

    private val storageInfo: String
        get() = runCatching {
            val dir = baseDir()
            "${formatGb(dir.usableSpace)} usable / ${formatGb(dir.totalSpace)} total"
        }.getOrDefault("unknown")

    private val batteryOptimized: String
        get() = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                "ignored (app exempt)"
            } else {
                "NOT ignored (schedule may be killed)"
            }
        }.getOrDefault("unknown")

    private val selinuxMode: String
        get() = runCatching {
            when (File("/sys/fs/selinux/enforce").readText().trim()) {
                "1" -> "enforcing"
                "0" -> "permissive"
                else -> "unknown"
            }
        }.getOrDefault("unknown")

    private fun formatGb(bytes: Long): String =
        "%.1f GB".format(Locale.US, bytes / 1024f / 1024f / 1024f)

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
        const val MIME_ZIP = "application/zip"
        const val BUFFER_SIZE = 8 * 1024

        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
