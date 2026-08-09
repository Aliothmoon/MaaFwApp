package com.aliothmoon.maafw.remote

import android.os.Build
import android.os.Environment
import android.os.Process
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.third.Ln
import java.io.File

/**
 * 服务进程启动诊断 trace（Shizuku / Root 用户服务进程侧）。
 *
 * 卡死发生在 [RemoteServiceImpl] 构造阶段（init 块里同步加载 libMaaCore.so），早于 setup(userDir)，
 * 此时进程既没有 Context 也拿不到 App 传来的外部路径。FakeContext.getExternalFilesDir() 解析的是
 * com.android.shell 的目录而非本应用，不可用。
 *
 * 因此这里用 Environment.getExternalStorageDirectory() + BuildConfig.APPLICATION_ID 自行推导出与
 * App 侧 getExternalFilesDir(null) 相同的路径：
 *   /storage/emulated/0/Android/data/{pkg}/files/Maa/debug/service_boot_debug.log
 * shell uid 对该目录可写（LogcatCaptureServiceImpl 已实测写 {userDir}/debug/...），文件与
 * root_launch_debug.log 同目录、可被 App 直接读取，无需 /data/local/tmp、无需跨进程拷贝。
 *
 * 全程 runCatching 兜底：推导/写入失败也不影响主流程（App 侧 service_bind_debug.log + logcat 仍可定位）。
 */
object RemoteBootTrace {

    private const val FILE_NAME = "service_boot_debug.log"
    private const val MAX_BYTES = 256 * 1024L

    private val lock = Any()

    @Volatile
    private var headerWritten = false

    private val traceFile: File by lazy {
        File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${BuildConfig.APPLICATION_ID}/files/${AppFiles.DEBUG_DIR}/$FILE_NAME"
        )
    }

    fun mark(stage: String, msg: String = "") {
        synchronized(lock) {
            runCatching {
                val file = traceFile
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.delete()
                    headerWritten = false
                }
                if (!headerWritten) {
                    file.parentFile?.mkdirs()
                    file.appendText(
                        "==== service boot pid=${Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                            "api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                            "t=${System.currentTimeMillis()} ====\n"
                    )
                    headerWritten = true
                }
                val line = if (msg.isEmpty()) {
                    "${System.currentTimeMillis()}  $stage\n"
                } else {
                    "${System.currentTimeMillis()}  $stage  $msg\n"
                }
                file.appendText(line)
            }
        }
        // 同时进 logcat / root 的 stderr 日志（Ln 写 FileDescriptor.out/err）。
        Ln.i("[BOOT] $stage${if (msg.isEmpty()) "" else " $msg"}")
    }
}
