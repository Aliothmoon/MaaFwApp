package com.aliothmoon.maafw.privileged

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.aliothmoon.maafw.SystemApkInstaller
import rikka.sui.Sui
import timber.log.Timber
import java.io.File

/** Shizuku 的安装状态探测、随包分发的 APK 安装与拉起 */
object ShizukuInstallHelper {

    /** Shizuku 官方包名，manifest 的 `<queries>` 里声明的就是它 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val ASSET_NAME = "shizuku.apk"

    enum class Status {
        /** binder 可用，服务正常 */
        Ready,

        /** 由 Sui（Magisk 模块）提供服务 */
        SuiAvailable,

        /** 已安装但服务没起来 */
        NotRunning,

        /** 都没检测到 */
        NotInstalled,
    }

    fun checkStatus(context: Context, launchPackage: String = SHIZUKU_PACKAGE): Status {
        val sui = runCatching { Sui.init(context.packageName) }.getOrDefault(false)
        if (sui) return Status.SuiAvailable
        if (ShizukuManager.isShizukuAvailable()) return Status.Ready
        return if (isInstalled(context, launchPackage)) Status.NotRunning else Status.NotInstalled
    }

    /**
     * 装随包带的 shizuku.apk，复用 [SystemApkInstaller]（同样走 FileProvider，
     * API 24 起 file:// 直接抛 FileUriExposedException）
     */
    suspend fun installShizuku(context: Context): Boolean {
        val apk = copyApkFromAssets(context) ?: return false
        return SystemApkInstaller(context).install(apk) is SystemApkInstaller.Result.Started
    }

    fun openShizuku(context: Context, launchPackage: String = SHIZUKU_PACKAGE): Boolean {
        if (launchPackage.isBlank()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(launchPackage) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Timber.w(it, "Failed to open Shizuku") }.getOrDefault(false)
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun copyApkFromAssets(context: Context): File? = runCatching {
        val dest = File(context.cacheDir, ASSET_NAME)
        context.assets.open(ASSET_NAME).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest
    }.onFailure { Timber.e(it, "Failed to read shizuku.apk from assets") }.getOrNull()
}
