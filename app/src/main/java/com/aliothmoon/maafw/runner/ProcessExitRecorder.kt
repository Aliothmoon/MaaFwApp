package com.aliothmoon.maafw.runner

import android.content.Context
import android.os.Build
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.remote.ProcessExitLog
import com.aliothmoon.maafw.remote.ProcessExitRecord
import com.aliothmoon.maafw.remote.ProcessExitSubject
import com.aliothmoon.maafw.remote.ProcessExitWire
import com.aliothmoon.maafw.remote.internal.ProcessExitQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 把系统记下的进程退出写进应用日志，按包名记水位只写一次
 *
 * App 查自己不要权限；游戏包名来自运行期看门狗，经提权服务查。所有失败都只写 warning，
 * 这条诊断链不能反过来阻断启动、任务或游戏退出后的收尾。
 */
class ProcessExitRecorder(
    context: Context,
    private val servicePort: PrivilegedServicePort,
) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()

    suspend fun recordSelf() {
        guarded(appContext.packageName) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded
            val records = withContext(MaaDispatchers.IO) {
                ProcessExitQuery.query(
                    context = appContext,
                    packageName = appContext.packageName,
                    sinceMs = 0L,
                    maxNum = ProcessExitLog.MAX_RECORDS,
                )
            }
            record(ProcessExitSubject.SELF, appContext.packageName, records)
        }
    }

    /**
     * 服务每次从断线恢复都补查最近一次见过的游戏包
     *
     * App 与游戏可以先后在后台被杀：当下没人能记，下一次启动连上提权进程时再取系统保存的退出记录。
     */
    suspend fun recordKnownGameOnServiceConnect() {
        servicePort.serviceState
            .filter { it == PrivilegedServiceState.Connected }
            .collect { recordKnownGame() }
    }

    suspend fun recordKnownGame() {
        val packageName = guarded("known game package") {
            withContext(MaaDispatchers.IO) {
                prefs.getString(KEY_LAST_GAME_PACKAGE, null)
            }
        }?.takeIf { it.isNotBlank() } ?: return
        recordGame(packageName)
    }

    /** 先落盘目标包名，App 随后被杀也能在下次启动补查游戏退出原因 */
    suspend fun rememberGamePackage(packageName: String) {
        val target = packageName.trim()
        if (target.isEmpty()) return
        guarded(target) {
            withContext(MaaDispatchers.IO) {
                prefs.edit().putString(KEY_LAST_GAME_PACKAGE, target).commit()
            }
        }
    }

    suspend fun recordGame(packageName: String) {
        val target = packageName.trim()
        if (target.isEmpty()) return
        guarded(target) {
            // 即使这次查询失败，下次连上后也要知道该补查哪个包
            rememberGamePackage(target)
            val since = withContext(MaaDispatchers.IO) {
                mutex.withLock { watermark(target)?.timestamp ?: 0L }
            }
            val raw = fetch(target, since) ?: return@guarded
            record(ProcessExitSubject.GAME, target, ProcessExitWire.decode(raw))
        }
    }

    private suspend fun <T> guarded(packageName: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.w(e, "record process exit failed: %s", packageName)
            null
        }
    }

    private suspend fun fetch(packageName: String, sinceMs: Long): String? {
        val service = servicePort.serviceOrNull() ?: return null
        return withContext(MaaDispatchers.IO) {
            runCatching {
                service.getProcessExitRecords(
                    packageName,
                    sinceMs,
                    ProcessExitLog.MAX_RECORDS,
                )
            }.onFailure {
                Timber.w(it, "query process exit reasons failed: %s", packageName)
            }.getOrNull()
        }
    }

    private suspend fun record(
        subject: ProcessExitSubject,
        packageName: String,
        records: List<ProcessExitRecord>,
    ) = withContext(MaaDispatchers.IO) {
        mutex.withLock {
            val current = watermark(packageName)
            val fresh = ProcessExitLog.selectNew(records, current)
            fresh.forEach { item ->
                val line = ProcessExitLog.format(subject, item)
                if (ProcessExitLog.isExpected(subject, item)) {
                    Timber.i(line)
                } else {
                    Timber.w(line)
                }
            }
            val next = fresh.lastOrNull()?.let { ProcessExitLog.Watermark(it.timestamp, it.pid) }
                ?: ProcessExitLog.Watermark(0L, 0).takeIf { current == null }
            if (next != null) saveWatermark(packageName, next)
        }
    }

    private fun watermark(packageName: String): ProcessExitLog.Watermark? {
        if (!prefs.contains(timestampKey(packageName))) return null
        return ProcessExitLog.Watermark(
            timestamp = prefs.getLong(timestampKey(packageName), 0L),
            pid = prefs.getInt(pidKey(packageName), 0),
        )
    }

    /** 同步落盘：刚写完日志就被杀，下次不至于再写一遍 */
    private fun saveWatermark(packageName: String, watermark: ProcessExitLog.Watermark) {
        prefs.edit()
            .putLong(timestampKey(packageName), watermark.timestamp)
            .putInt(pidKey(packageName), watermark.pid)
            .commit()
    }

    private fun timestampKey(packageName: String): String = "$packageName.ts"

    private fun pidKey(packageName: String): String = "$packageName.pid"

    private companion object {
        const val PREFS_NAME = "process_exit_watermark"
        const val KEY_LAST_GAME_PACKAGE = "last_game_package"
    }
}
