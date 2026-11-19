package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import com.aliothmoon.maafw.privileged.callWithTimeout

/**
 * 进程死因补记的唯一入口：App 自身的退出在启动时记，上一个特权进程的死因等新进程连上后由它翻系统日志
 *
 * 取证写进 [dir]，留最新 [KEEP] 份，随日志导出；app.log 只记一行摘要。都不进遥测
 */
internal class ProcessForensics(
    private val servicePort: PrivilegedServicePort,
    private val recorder: ProcessExitRecorder,
    private val forensics: PrivilegedDeathForensics,
    private val dir: () -> File,
    /** 特权进程名都以它开头（`包名:`），系统日志按它认死者 */
    private val processPrefix: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun start(scope: CoroutineScope) {
        scope.launch { recorder.recordSelf() }
        scope.launch {
            // 死讯到的时候服务面已经清掉了，pid 只能在连上时先记着
            var connectedPid: Int? = null
            servicePort.serviceState.collect { state ->
                when (state) {
                    PrivilegedServiceState.Connected -> {
                        val service = servicePort.serviceOrNull() ?: return@collect
                        val pid = servicePort.callWithTimeout(PID_TIMEOUT) { it.pid() }
                        connectedPid = pid
                        // 翻日志最长十来秒，在 collect 里等的话这期间再死一次，Died 会被合并掉
                        forensics.take(pid)?.let { pending -> scope.launch { dump(service, pending) } }
                    }

                    PrivilegedServiceState.Died -> connectedPid?.let {
                        forensics.remember(PrivilegedDeathForensics.Pending(clock(), it))
                        connectedPid = null
                    }

                    else -> Unit
                }
            }
        }
    }

    private suspend fun dump(service: RemoteService, pending: PrivilegedDeathForensics.Pending) {
        val delaySec = (clock() - pending.diedAtMs) / 1000
        val text = withContext(MaaDispatchers.IO) {
            // 旧版特权进程没有这个方法，proxy 读回 null
            runCatching { service.dumpSystemLog(pending.sinceMs, pending.untilMs, pending.pid, processPrefix).orEmpty() }
        }.getOrElse {
            Timber.w(it, "dump system log for dead privileged process %d failed", pending.pid)
            return
        }
        withContext(MaaDispatchers.IO) {
            runCatching {
                val target = dir().apply { mkdirs() }
                val file = File(target, "death_${STAMP.format(Date(pending.diedAtMs))}_${pending.pid}.txt")
                file.writeText("# privileged process pid=${pending.pid} died, fetched ${delaySec}s later\n$text")
                prune(target)
                file
            }
        }.onSuccess { file ->
            Timber.w(
                "privileged process %d died %ds ago, system log forensics in %s: %s",
                pending.pid, delaySec, file.name, text.lineSequence().firstOrNull().orEmpty(),
            )
        }.onFailure { Timber.w(it, "write privileged death forensics failed") }
    }

    private fun prune(target: File) {
        target.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val PREFIX = "death_"
        const val KEEP = 10
        val PID_TIMEOUT = 3.seconds
        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
