package com.aliothmoon.maafw.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

internal class SystemUpdateInstaller(
    private val context: Context,
    private val downloadDirectory: File,
) : UpdateInstallApi {

    override suspend fun install(update: DownloadedUpdate): UpdateInstallResult =
        withContext(MaaDispatchers.IO) {
            val file = try {
                update.file.canonicalFile
            } catch (_: IOException) {
                null
            } ?: return@withContext failed(
                UpdateInstallFailure.FILE_INVALID,
                "Update APK path cannot be resolved",
            )
            if (!isInstallable(file)) {
                return@withContext failed(
                    UpdateInstallFailure.FILE_INVALID,
                    "Update APK is missing, empty, or outside the update directory",
                )
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
                UpdateInstallResult.InstallerStarted
            } catch (e: ActivityNotFoundException) {
                failed(UpdateInstallFailure.INSTALLER_NOT_FOUND, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(UpdateInstallFailure.UNKNOWN, e.message)
            }
        }

    private fun isInstallable(file: File?): Boolean {
        if (file == null) return false
        if (!file.name.endsWith(".apk", ignoreCase = true)) return false
        if (!file.isFile || file.length() <= 0L) return false
        val directory = try {
            downloadDirectory.canonicalFile
        } catch (_: IOException) {
            return false
        }
        return file.parentFile == directory
    }

    private fun failed(
        reason: UpdateInstallFailure,
        message: String?,
    ): UpdateInstallResult.Failed {
        Timber.w(message ?: reason.name)
        return UpdateInstallResult.Failed(reason, message)
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
