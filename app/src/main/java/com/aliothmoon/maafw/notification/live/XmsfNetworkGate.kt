package com.aliothmoon.maafw.notification.live

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.edit
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 经特权进程断 / 复 com.xiaomi.xmsf 的联网（移植自 MaaMeow）
 *
 * 超级岛的云端鉴权断网即放行，联网时没在白名单里会被系统撤岛；所以进度岛挂着期间断开它，
 * 测试通知只断一下（[pulse]）。断不成也照样发岛，只是可能被撤
 *
 * 规则落在系统侧 netd，进程死了也不会自己失效：断网先落持久标记，恢复成功才清，
 * 特权进程每次重连都补一次恢复——否则特权进程先死就会让小米推送永久断网
 */
class XmsfNetworkGate(
    context: Context,
    private val servicePort: PrivilegedServicePort,
    scope: CoroutineScope,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val worker = HandlerThread("xmsf-gate").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private var holds = 0

    init {
        scope.launch {
            servicePort.serviceState
                .filter { it == PrivilegedServiceState.Connected }
                .collect { repairIfPending() }
        }
    }

    /** 含跨进程 shell 往返，调用方须在非主线程 */
    fun acquire() {
        synchronized(lock) {
            if (holds++ == 0) apply(enabled = false)
        }
    }

    fun release() {
        synchronized(lock) {
            if (holds <= 0) return
            if (--holds == 0) workerHandler.post { restoreIfIdle() }
        }
    }

    /** 断网窗口要盖住云端鉴权的失败路径，恢复延后 1.5s；调用方须在非主线程 */
    fun pulse(block: () -> Unit) {
        acquire()
        try {
            block()
        } finally {
            workerHandler.postDelayed({ release() }, RESTORE_DELAY_MS)
        }
    }

    private fun repairIfPending() {
        if (synchronized(lock) { holds != 0 }) return
        if (!prefs.getBoolean(KEY_CUT, false)) return
        Timber.w("xmsf cut outstanding from a previous session, repairing")
        workerHandler.post { restoreIfIdle() }
    }

    private fun restoreIfIdle() {
        if (synchronized(lock) { holds != 0 }) return
        apply(enabled = true)
    }

    private fun apply(enabled: Boolean) {
        val service = servicePort.serviceOrNull()
        if (service == null) {
            // 恢复时没连上：标记留着，等重连时 repairIfPending 补
            Timber.w("xmsf networking toggle skipped (enabled=%s): privileged service not connected", enabled)
            return
        }
        // 先落标记再下发：调用途中进程被杀也不会丢掉「断过网」这件事
        if (!enabled) markCut(true)
        val result = runCatching { service.setPackageNetworkingEnabled(XMSF_PACKAGE, enabled) }
            .onFailure { Timber.w(it, "xmsf networking toggle failed enabled=%s", enabled) }
        val ok = result.getOrDefault(false)
        Timber.i("xmsf networking enabled=%s result=%s", enabled, ok)
        when {
            enabled && ok -> markCut(false)
            enabled -> Timber.w("xmsf restore failed, will retry on next privileged connect")
            // 断网明确失败时特权侧已自清；抛异常则可能已生效，标记得留着
            result.isSuccess && !ok -> markCut(false)
        }
    }

    private fun markCut(cut: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_CUT, cut) }
    }

    private companion object {
        const val XMSF_PACKAGE = "com.xiaomi.xmsf"
        const val RESTORE_DELAY_MS = 1_500L
        const val PREFS_NAME = "live_xmsf_gate"
        const val KEY_CUT = "cut_outstanding"
    }
}
