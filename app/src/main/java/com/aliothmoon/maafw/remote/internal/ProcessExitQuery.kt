package com.aliothmoon.maafw.remote.internal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.aliothmoon.maafw.remote.ProcessExitLog
import com.aliothmoon.maafw.remote.ProcessExitRecord
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln

/** 查任意包的进程退出记录；跨包需要 DUMP 权限，shell/root 身份自带 */
object ProcessExitQuery {
    private const val TAG = "ProcessExitQuery"
    private val REASON = Regex("""\breason=-?\d+ \(([^)]*)\)""")
    private val DETAIL = Regex("""\bsubreason=(-?\d+) \(([^)]*)\)""")

    fun query(
        context: Context,
        packageName: String?,
        sinceMs: Long,
        maxNum: Int,
    ): List<ProcessExitRecord> {
        if (packageName.isNullOrBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        val limit = maxNum.coerceIn(1, ProcessExitLog.MAX_RECORDS)
        return runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, limit)
                .filter { it.timestamp >= sinceMs }
                .map { from(it, withHiddenDetail = false) }
        }.onFailure {
            Ln.w("$TAG: query $packageName failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun queryAsShell(packageName: String?, sinceMs: Long, maxNum: Int): List<ProcessExitRecord> {
        if (packageName.isNullOrBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        val limit = maxNum.coerceIn(1, ProcessExitLog.MAX_RECORDS)
        return runCatching {
            @Suppress("DEPRECATION")
            FakeContext.get().getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, limit)
                .filter { it.timestamp >= sinceMs }
                .map { from(it, withHiddenDetail = true) }
        }.onFailure {
            Ln.w("$TAG: shell query $packageName failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun from(info: ApplicationExitInfo, withHiddenDetail: Boolean): ProcessExitRecord {
        val raw = info.toString()
        val detailMatch = DETAIL.find(raw)
        var detail = detailMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var detailName = detailMatch?.groupValues?.get(2).orEmpty()
        if (withHiddenDetail) {
            runCatching {
                val type = ApplicationExitInfo::class.java
                detail = type.getDeclaredMethod("getSubReason").invoke(info) as Int
                detailName = runCatching {
                    type.getDeclaredMethod("subreasonToString", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(null, detail) as String
                }.getOrNull() ?: detailName
            }
        }
        return ProcessExitRecord(
            timestamp = info.timestamp,
            pid = info.pid,
            processName = info.processName.orEmpty(),
            reason = info.reason,
            reasonName = REASON.find(raw)?.groupValues?.get(1).orEmpty(),
            detail = detail,
            detailName = detailName,
            status = info.status,
            importance = info.importance,
            pssKb = info.pss,
            rssKb = info.rss,
            description = info.description,
        )
    }
}
