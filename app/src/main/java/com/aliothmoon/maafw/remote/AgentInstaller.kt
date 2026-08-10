package com.aliothmoon.maafw.remote

import android.os.Build
import android.os.Process
import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.util.zip.ZipFile

/**
 * 把 APK 里的 agent 运行时解包到可执行目录
 *
 * 解包必须在特权进程做：落点 `/data/local/tmp` 是 shell 属主，app 进程进不去；
 * 而外部私有目录（PI 的落点）挂载带 noexec 且 sdcardfs 的 mask 会抹掉 x 位，解释器和 `.so` 都用不了
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
        val entries = readManifest()
        if (entries.isEmpty()) {
            throw AgentLaunchException("本包未带 agent 运行时（assets 里没有 $MANIFEST_ASSET）")
        }
        val abi = supportedAbis.firstOrNull { abi -> entries.any { it.startsWith("$abi/") } }
            ?: throw AgentLaunchException(
                "agent 运行时不覆盖本机 ABI：设备支持 $supportedAbis",
            )

        val target = File(BASE_DIR, "$DIR_NAME/$abi")
        val marker = File(BASE_DIR, "$DIR_NAME/$MARKER_NAME")
        val stamp = "${readFingerprint()}:$uid"
        if (target.isDirectory && marker.isFile && marker.readText().trim() == stamp) {
            return target
        }

        // 顺序与 PiInstaller 一致：标记先失效，解包中途掉电时残留内容不会被当成完整的一份
        marker.delete()
        target.deleteRecursively()
        target.mkdirs()
        unpack(abi, entries.filter { it.startsWith("$abi/") }, target)
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        Ln.i("AgentInstaller: unpacked $abi runtime to ${target.absolutePath}")
        return target
    }

    /**
     * 单线程解包：ZipFile 的并发 getInputStream 在 Android 上没有明确保证，
     * 要并行得每线程各开一个 ZipFile，先不做——这是每次换包只跑一次的路径
     */
    private fun unpack(abi: String, entries: List<String>, dest: File) {
        val prefix = "$abi/"
        ZipFile(apkPath).use { zip ->
            entries.forEach { entry ->
                val source = zip.getEntry("$ASSET_DIR/$entry")
                    ?: throw AgentLaunchException("agent 运行时清单与 assets 不符：缺 $entry")
                val outFile = File(dest, entry.removePrefix(prefix))
                outFile.parentFile?.mkdirs()
                zip.getInputStream(source).use { input ->
                    outFile.outputStream().use(input::copyTo)
                }
                // 逐个判哪些该可执行不如整棵树都给：漏标一个就要到 exec 那一刻才报错，
                // 而这棵树本来就在只有特权身份进得去的目录里
                outFile.setExecutable(true, true)
            }
        }
    }

    private fun readManifest(): List<String> = readAsset(MANIFEST_ASSET)
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toList()
        .orEmpty()

    private fun readFingerprint(): String =
        readAsset(FINGERPRINT_ASSET)?.trim()?.takeIf(String::isNotEmpty) ?: UNKNOWN_FINGERPRINT

    private fun readAsset(name: String): String? = runCatching {
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("assets/$name") ?: return null
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.onFailure { Ln.w("AgentInstaller: read $name failed: ${it.message}") }.getOrNull()

    private companion object {
        /** shell 与 root 都可写可执行；实测 `/data` 是 ext4，挂载项里没有 noexec */
        val BASE_DIR = File("/data/local/tmp")
        const val DIR_NAME = "maafw-agent"
        const val MARKER_NAME = "runtime.fingerprint"
        const val ASSET_DIR = "assets/agent"
        const val MANIFEST_ASSET = "agent.manifest"
        const val FINGERPRINT_ASSET = "agent.fingerprint"

        /** 缺指纹说明这个包没经过 syncAgentAssets；回落固定值即「永不过期」 */
        const val UNKNOWN_FINGERPRINT = "unknown"
    }
}
