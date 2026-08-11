package com.aliothmoon.maafw.remote

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.aliothmoon.maafw.constant.ShellDirs
import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 把 APK 里的 agent 运行时归档解包到可执行目录
 *
 * 解包必须在特权进程做：落点 `/data/local/tmp` 是 shell 属主，app 进程进不去；
 * 而外部私有目录（PI 的落点）挂载带 noexec 且 sdcardfs 的 mask 会抹掉 x 位，解释器和 `.so` 都用不了
 *
 * 运行时是**一个归档**而不是散装 assets：AAPT 会按默认规则改写 assets——`<dir>_*` 整目录丢掉
 * （Python 的 `_pyrepl/` 首当其冲）、`.*` 丢掉、`.gz` 解压后改名。实测 1554 个条目进包只剩 1272 个
 *
 * 标记同时记指纹与运行身份：换了 PI 包要重解，root 与 Shizuku 之间换了后端也要重解——
 * 上一轮留下的文件属主对不上，直接沿用会在 exec 时才报错
 */
class AgentInstaller(
    private val apkPath: String,
    /** 默认取本进程 uid；换后端即变，用它兜住属主不符的情况 */
    private val uid: Int = Process.myUid(),
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.orEmpty().toList(),
) {

    /**
     * 返回可直接拼路径的 bundle 根，必要时先解包
     * 阻塞 IO：调用方须在 MaaRunner 的工作线程上，不能在 binder 线程
     */
    @Synchronized
    fun ensureInstalled(): File {
        val fingerprint = readAsset(FINGERPRINT_ASSET)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw AgentLaunchException("本包未带 agent 运行时（assets 里没有 $FINGERPRINT_ASSET）")

        val abis = readArchiveAbis()
        val abi = supportedAbis.firstOrNull(abis::contains)
            ?: throw AgentLaunchException(
                "agent 运行时不覆盖本机 ABI：归档里有 $abis，设备支持 $supportedAbis",
            )

        val target = File(ShellDirs.AGENT_DIR, abi)
        val marker = File(ShellDirs.AGENT_DIR, MARKER_NAME)
        val stamp = "$fingerprint:$uid"
        if (target.isDirectory && marker.isFile && marker.readText().trim() == stamp) {
            return target
        }

        // 顺序与 PiInstaller 一致：标记先失效，解包中途掉电时残留内容不会被当成完整的一份
        marker.delete()
        target.deleteRecursively()
        target.mkdirs()
        val startedAt = SystemClock.elapsedRealtime()
        val count = unpack(abi, target)
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        Ln.i(
            "AgentInstaller: unpacked $abi runtime, $count files in " +
                "${SystemClock.elapsedRealtime() - startedAt}ms -> ${target.absolutePath}",
        )
        return target
    }

    /**
     * 顺序流式解包
     *
     * 归档在 APK 里是 STORED（`androidResources.noCompress`），读它不必先 inflate 整条目；
     * 内层才是 deflate，与 Chaquopy 的 `.imy` 同一个路数——全程只解压一次
     * 不做并行：ZipInputStream 本身是顺序的，要并行得先把内层归档落地再随机访问，
     * 多一遍几十 MB 的写盘，不划算
     */
    private fun unpack(abi: String, dest: File): Int {
        val prefix = "$abi/"
        var count = 0
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        openArchive().use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !name.startsWith(prefix)) {
                    zip.closeEntry()
                    continue
                }
                val outFile = File(dest, name.removePrefix(prefix))
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> zip.copyTo(output, buffer) }
                // 逐个判哪些该可执行不如整棵树都给：漏标一个就要到 exec 那一刻才报错，
                // 而这棵树本来就在只有特权身份进得去的目录里
                outFile.setExecutable(true, true)
                zip.closeEntry()
                count++
            }
        }
        if (count == 0) throw AgentLaunchException("agent 运行时归档里没有 $abi 的内容")
        return count
    }

    /** 归档顶层目录名即 ABI；只读条目名，不解内容 */
    private fun readArchiveAbis(): Set<String> = buildSet {
        openArchive().use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entry.name.substringBefore('/').takeIf(String::isNotEmpty)?.let(::add)
                zip.closeEntry()
            }
        }
    }

    private fun openArchive(): ZipInputStream {
        val apk = ZipFile(apkPath)
        val entry = apk.getEntry(BUNDLE_ASSET)
            ?: run {
                apk.close()
                throw AgentLaunchException("本包未带 agent 运行时（assets 里没有 $BUNDLE_ASSET）")
            }
        // ZipFile 得活到流读完；关流时一并关掉它
        return object : ZipInputStream(apk.getInputStream(entry)) {
            override fun close() {
                super.close()
                apk.close()
            }
        }
    }

    private fun readAsset(name: String): String? = runCatching {
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("assets/$name") ?: return null
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.onFailure { Ln.w("AgentInstaller: read $name failed: ${it.message}") }.getOrNull()

    private fun InputStream.copyTo(out: OutputStream, buffer: ByteArray) {
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        const val MARKER_NAME = "runtime.fingerprint"
        const val BUNDLE_ASSET = "assets/agent/bundle.zip"
        const val FINGERPRINT_ASSET = "agent.fingerprint"

        const val COPY_BUFFER_BYTES = 128 * 1024
    }
}
