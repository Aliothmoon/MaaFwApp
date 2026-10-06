package com.aliothmoon.maafw.remote.internal

import android.app.ActivityManager
import android.os.Process
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln

enum class ProcessLiveness {
    ALIVE,
    DEAD,
    UNKNOWN;

    companion object {

        /**
         * 两路都说没有才算死。会 fork 一个 pidof，调用方自己限频
         *
         * pidof 只认与包名同名的进程，Activity 跑在 `包名:xxx` 或自定义进程名里时会把活着的应用报成死的，
         * 所以它说死了还要过一遍进程表
         */
        fun probe(packageName: String): ProcessLiveness {
            val byPidof = pidof(packageName)
            if (byPidof == ALIVE) return ALIVE
            return when (listedInProcessTable(packageName)) {
                true -> ALIVE
                false -> byPidof
                null -> UNKNOWN
            }
        }

        /**
         * 判活走 pidof（与 MaaMeow 同法）：调用方都跑在特权进程里，shell 身份直接 exec 即可
         *
         * 只有"退出码 1 且两个流都空"才算确认死亡——ROM 换了 pidof 实现、或权限被挡时，
         * 输出形态五花八门，一律当判不出，宁可漏报也不要把还活着的应用报成死了
         */
        private fun pidof(packageName: String): ProcessLiveness = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            val exitCode = process.waitFor()
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            when {
                exitCode == 0 && out.isNotEmpty() -> ALIVE
                exitCode == 1 && out.isEmpty() && err.isEmpty() -> DEAD
                else -> {
                    Ln.w("ProcessLiveness: pidof $packageName unexpected: exit=$exitCode out=$out err=$err")
                    UNKNOWN
                }
            }
        }.getOrElse {
            Ln.w("ProcessLiveness: pidof $packageName failed: ${it.message}")
            UNKNOWN
        }

        /** 进程表里只有自己的进程，是被权限挡了，不能当成「别人都不在」，返回 null */
        private fun listedInProcessTable(packageName: String): Boolean? = runCatching {
            val am = FakeContext.get().getSystemService(ActivityManager::class.java) ?: return null
            val processes = am.runningAppProcesses ?: return null
            if (processes.none { it.uid != Process.myUid() }) return null
            processes.any { process ->
                process.processName == packageName ||
                    process.processName.startsWith("$packageName:") ||
                    process.pkgList?.contains(packageName) == true
            }
        }.onFailure { Ln.w("ProcessLiveness: process table unavailable: ${it.message}") }.getOrNull()
    }
}
