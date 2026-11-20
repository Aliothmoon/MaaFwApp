package com.aliothmoon.maafw.remote.internal

import android.system.Os
import java.io.File

/**
 * 特权进程读不到某个目录时，分清是属主不对（root 建的目录）还是存储栈拦的（挂载方式）
 *
 * 只给日志用：结果是一句可读的诊断，不参与判定
 */
internal object PathDiagnosis {

    fun diagnose(path: File): String {
        val existing = generateSequence(path.absoluteFile) { it.parentFile }.firstOrNull { it.exists() }
        val owner = existing?.let { runCatching { Os.stat(it.path).st_uid }.getOrNull() }
        val mounts = runCatching { File("/proc/self/mountinfo").readLines() }.getOrDefault(emptyList())
        return "owner of $existing=${owner ?: "?"}, mount=${mountOf(path.absolutePath, mounts)}"
    }

    /** mountinfo 里覆盖 [path] 的最深挂载点及其文件系统类型 */
    internal fun mountOf(path: String, mountInfo: List<String>): String {
        var best: Pair<String, String>? = null
        for (line in mountInfo) {
            val fields = line.split(' ')
            val point = fields.getOrNull(4) ?: continue
            val separator = fields.indexOf("-")
            val fsType = fields.getOrNull(separator + 1)?.takeIf { separator > 4 } ?: continue
            val covers = point == "/" || path == point || path.startsWith("$point/")
            if (covers && point.length >= (best?.first?.length ?: -1)) best = point to fsType
        }
        return best?.let { "${it.first} ${it.second}" } ?: "?"
    }
}
