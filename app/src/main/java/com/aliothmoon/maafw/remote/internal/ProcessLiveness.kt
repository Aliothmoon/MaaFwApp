package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.third.Ln

enum class ProcessLiveness {
    ALIVE,
    DEAD,
    UNKNOWN;

    companion object {

        /**
         * 判活走 pidof（与 MaaMeow 同法）：调用方都跑在特权进程里，shell 身份直接 exec 即可
         *
         * 只有"退出码 1 且两个流都空"才算确认死亡——ROM 换了 pidof 实现、或权限被挡时，
         * 输出形态五花八门，一律当判不出，宁可漏报也不要把还活着的应用报成死了
         */
        fun of(packageName: String): ProcessLiveness = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            val exitCode = process.waitFor()
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            when {
                exitCode == 0 && out.isNotEmpty() -> ALIVE
                exitCode == 1 && out.isEmpty() && err.isEmpty() -> DEAD
                else -> {
                    Ln.w("pidof $packageName unexpected: exit=$exitCode out=$out err=$err")
                    UNKNOWN
                }
            }
        }.getOrElse {
            Ln.w("pidof $packageName failed: ${it.message}")
            UNKNOWN
        }
    }
}
