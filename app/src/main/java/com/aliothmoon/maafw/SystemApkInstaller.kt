package com.aliothmoon.maafw

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 把本地 APK 交给系统安装器（FileProvider + ACTION_VIEW）
 *
 * 更新下载（cacheDir/updates）与随包 Shizuku（cacheDir 根）共用；只装自己 cacheDir
 * 里的文件。走到「系统安装器被拉起」为止，装不装成由系统 UI 与用户决定
 */
class SystemApkInstaller(
    private val context: Context,
) {

    enum class Failure(@param:StringRes val messageRes: Int) {
        FILE_INVALID(R.string.update_install_fail_file_invalid),
        INSTALLER_NOT_FOUND(R.string.update_install_fail_installer_not_found),
        UNKNOWN(R.string.update_install_fail_unknown),
    }

    sealed interface Result {
        data object Started : Result
        data class Failed(val reason: Failure) : Result
    }

    suspend fun install(apk: File): Result = withContext(Dispatchers.IO) {
        val file = try {
            apk.canonicalFile
        } catch (_: IOException) {
            null
        }
        if (file == null || !isInstallable(file)) {
            return@withContext failed(Failure.FILE_INVALID, "APK is missing, empty, or outside the app cache")
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.Started
        } catch (e: ActivityNotFoundException) {
            failed(Failure.INSTALLER_NOT_FOUND, e.message)
        } catch (e: Exception) {
            failed(Failure.UNKNOWN, e.message)
        }
    }

    private fun isInstallable(file: File?): Boolean {
        if (file == null) return false
        if (!file.name.endsWith(".apk", ignoreCase = true)) return false
        if (!file.isFile || file.length() <= 0L) return false
        val cacheRoot = try {
            context.cacheDir.canonicalFile
        } catch (_: IOException) {
            return false
        }
        return file.parentFile != null && file.absolutePath.startsWith(cacheRoot.absolutePath + File.separator)
    }

    private fun failed(reason: Failure, logMessage: String?): Result.Failed {
        Timber.w("%s: %s", reason.name, logMessage)
        return Result.Failed(reason)
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
