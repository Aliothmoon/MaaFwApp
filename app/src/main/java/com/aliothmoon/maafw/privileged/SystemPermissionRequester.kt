package com.aliothmoon.maafw.privileged

import android.app.Activity
import android.content.Context
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * 首页权限卡展示的系统权限
 *
 * 特权进程上线即代授全集；这几项是特权进程没起来时给用户手点的兜底。
 * app 进程一死，特权进程的看门狗就自杀并释放虚拟屏——实测 MIUI 的
 * `ProcessManager: SwipeUpClean` 会按 Adj=905 直接 force-stop
 */
enum class SystemPermission {
    Notification,
    BatteryWhitelist,
    Overlay,
    Storage,
    Accessibility,
}

/**
 * 无状态的权限探测与请求，与 [ShizukuInstallHelper] 同一路子：需要 Activity 的动作由 Route 层执行
 *
 * 走 XXPermissions 而不是自己拼 Intent：MIUI 的电池优化白名单判定与系统页跳转都有偏差
 */
object SystemPermissionRequester {

    fun isGranted(context: Context, permission: SystemPermission): Boolean = runCatching {
        XXPermissions.isGrantedPermission(context, permission.toPlatform())
    }.onFailure { Timber.w(it, "读取权限状态失败: $permission") }.getOrDefault(false)

    suspend fun request(activity: Activity, permission: SystemPermission): Boolean {
        if (isGranted(activity, permission)) return true
        return suspendCancellableCoroutine { cont ->
            XXPermissions.with(activity)
                .permission(permission.toPlatform())
                .request { granted, _ -> cont.resume(granted.isNotEmpty()) }
        }
    }

    private fun SystemPermission.toPlatform(): IPermission = when (this) {
        SystemPermission.Notification -> PermissionLists.getPostNotificationsPermission()
        SystemPermission.BatteryWhitelist ->
            PermissionLists.getRequestIgnoreBatteryOptimizationsPermission()
        SystemPermission.Overlay -> PermissionLists.getSystemAlertWindowPermission()
        SystemPermission.Storage -> PermissionLists.getManageExternalStoragePermission()
        SystemPermission.Accessibility ->
            PermissionLists.getBindAccessibilityServicePermission(AccessibilityHelperService::class.java)
    }
}
