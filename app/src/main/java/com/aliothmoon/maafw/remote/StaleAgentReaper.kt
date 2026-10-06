package com.aliothmoon.maafw.remote

import android.os.Process
import com.aliothmoon.maafw.runner.PiAgentEnv
import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 杀掉 agent_exec.c 管不到的孤儿：套上 PDEATHSIG 之前的旧版、别的包留下的 agent
 *
 * 认孤儿只看父进程已是 init、环境里带 [PiAgentEnv.CLIENT_ENV_ENTRY]；活着的特权进程的 child 父进程不是 1。
 * 只在特权进程起来时扫一次：会抢端口的是旧 agent，本进程拉起的已由 PDEATHSIG 兜住
 */
object StaleAgentReaper {

    private val reaped = AtomicBoolean(false)

    fun reapOnce() {
        if (!reaped.compareAndSet(false, true)) return
        val pids = File("/proc").list()?.mapNotNull(String::toIntOrNull) ?: return
        pids.forEach { pid ->
            val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return@forEach
            if (parentPid(status) != 1) return@forEach
            val environ = runCatching { File("/proc/$pid/environ").readBytes() }.getOrNull() ?: return@forEach
            if (!hasMarker(environ)) return@forEach

            val cmdline = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ').trim() }
                .getOrDefault("")
            Ln.w("StaleAgentReaper: killing orphan agent pid=$pid cmd=$cmdline")
            Process.killProcess(pid)
        }
    }

    internal fun parentPid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("PPid:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    internal fun hasMarker(environ: ByteArray): Boolean =
        String(environ, Charsets.UTF_8).split('\u0000').any { it == PiAgentEnv.CLIENT_ENV_ENTRY }
}
