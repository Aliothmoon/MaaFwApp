package com.aliothmoon.maafw.remote

import android.util.Log
import com.aliothmoon.maafw.ILogcatService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 跑在独立特权进程（:logcat / :root_logcat）里的 logcat 抓取实现
 *
 * shell/root 身份自带 log 组权限，直接 `logcat -T 10 --pid=<pid>` 管道落盘；
 * 看门狗按 /proc 探活，目标进程消失即停掉它的 logcat（对齐 MaaMeow）
 */
class LogcatCaptureServiceImpl : ILogcatService.Stub() {

    companion object {
        private const val TAG = "LogcatCapture"
    }

    private val watchTargets = ConcurrentHashMap<Int, Process>()

    init {
        Thread {
            while (true) {
                Thread.sleep(5000)
                watchTargets.forEach { (pid, process) ->
                    if (!File("/proc/$pid").exists()) {
                        Log.i(TAG, "PID $pid gone, stopping its logcat")
                        process.destroyForcibly()
                        watchTargets.remove(pid)
                    }
                }
            }
        }.apply { name = "logcat-watchdog"; isDaemon = true }.start()
    }

    override fun destroy() = exitProcess(0)

    override fun startCapture(appPid: Int, servicePid: Int, userDir: String) {
        val debugDir = File(userDir, "debug").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        if (!watchTargets.containsKey(servicePid)) {
            val coreDir = File(debugDir, "logcat/core").apply { mkdirs() }
            val coreLog = File(coreDir, "logcat_$timestamp.log")
            Log.i(TAG, "Capturing core PID $servicePid -> ${coreLog.absolutePath}")
            watchTargets[servicePid] = pipeLogcat(servicePid, coreLog)
        }

        if (!watchTargets.containsKey(appPid)) {
            val appDir = File(debugDir, "logcat/app").apply { mkdirs() }
            val appLog = File(appDir, "logcat_$timestamp.log")
            Log.i(TAG, "Capturing app PID $appPid -> ${appLog.absolutePath}")
            watchTargets[appPid] = pipeLogcat(appPid, appLog)
        }
    }

    private fun pipeLogcat(pid: Int, outFile: File): Process {
        val process = ProcessBuilder("logcat", "-T", "10", "--pid=$pid")
            .redirectErrorStream(true)
            .start()

        Thread {
            try {
                process.inputStream.use { input ->
                    FileOutputStream(outFile, true).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pipeLogcat pid=$pid failed: ${e.message}")
            }
        }.apply { name = "logcat-reader-$pid" }.start()

        return process
    }
}
