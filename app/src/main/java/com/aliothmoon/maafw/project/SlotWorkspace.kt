package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.constant.AppPaths
import timber.log.Timber
import java.io.File

/**
 * 每一路各自的可写工作目录
 *
 * 为什么需要它：PI 解包树（见 [PiInstaller]）全局只有一份，而 agent child 是拿它当 cwd 起来的
 * ——`agent/main.py` 自己会把 cwd 切到「本文件往上两级」。agent 在这棵目录里会写
 * `config/pip_config.json`（依赖安装记录）、`.venv/`（虚拟环境），动作脚本也会往里落产物。
 * 多路同跑时这些写落同一份目录上、彼此覆盖，一条路存了什么另一条路看不出来。
 *
 * 分工是「读共用、写分家」：
 * - 复制过来的是 agent 和 MaaFramework 真正要读的内容：`agent/`、`tasks/`、`resource/`、`interface.json`
 * - `resource/`（识别模板）必须复制：MaaFramework 以 slot 工作目录为 cwd 加载资源，
 *   不复制会导致新加的流水线节点报 invalid node id；每路一份确保资源版本与任务配置一致
 * - 本路自己产出的（`config/`、`.venv/`、`debug/`、动作脚本的 json 配置）永不被同步覆盖
 *
 * PI 换版后靠 `pi.version`（[PiInstaller.PI_MARKER_NAME]，内容是出包时的 versionCode）判断要不要重同步，
 * 与解包树同一套口径。
 *
 * 阻塞 IO：方法直接读写文件，调用方必须在 IO 线程上
 */
object SlotWorkspace {

    /** 外部私有目录下挂载各实例工作目录的那一层 */
    private const val DIR_NAME = "pi_run"

    /**
     * 从 PI 根复制过来的条目
     *
     * 必须包含 `resource`：MaaFramework 以 slot 工作目录为 cwd 加载识别模板，
     * 不复制会导致流水线节点找不到（invalid node id）
     */
    private val COPIED_ENTRIES = listOf("agent", "tasks", "resource", "interface.json")

    /**
     * 顶层就以本路为准的目录名：agent 与动作脚本会往里写，同步时整棵跳过
     */
    private val SESSION_DIRS = setOf("config", ".venv", "debug", "logs")

    /** 动作脚本的配置：包里带一份当模板，但用户改过的那份不该被下次同步冲掉 */
    private const val ACTION_CONFIG_PREFIX = "agent/custom/action/"

    /** 取某一实例的工作目录（不保证已就绪） */
    fun rootFor(slot: Int): File = File(File(AppPaths.ROOT, DIR_NAME), "slot_$slot")

    /**
     * 保证这一路的工作目录存在、且与当前 PI 同步；返回可直接当 cwd 用的根
     *
     * 已就绪（标记与 PI 一致）时只做一次字符串比较就返回，不扫内容
     */
    fun ensureInstalled(pi: File, slot: Int): File {
        val root = rootFor(slot)
        val stamp = readStamp(pi)
        if (root.isDirectory && readMarker(root) == stamp) return root

        root.mkdirs()
        COPIED_ENTRIES.forEach { entry ->
            val from = File(pi, entry)
            if (!from.exists()) return@forEach
            copyInto(from, File(root, entry), entry)
        }
        // agent 用相对 cwd 的 ./config 存自己的记录，先把位置留出来
        File(root, "config").mkdirs()
        writeMarker(root, stamp)
        Timber.i("SlotWorkspace: slot %d 工作目录就绪 %s (stamp=%s)", slot, root.absolutePath, stamp)
        return root
    }

    /**
     * 删掉某一实例的工作目录，实例被删除时调用
     *
     * 号位会被下一个新实例复用，旧目录留着会让新实例一上来就带着上一任的 config、
     * .venv 与动作配置
     */
    fun erase(slot: Int) {
        val root = rootFor(slot)
        if (!root.exists()) return
        runCatching { root.deleteRecursively() }
            .onFailure { Timber.w(it, "SlotWorkspace: 删除工作目录失败 %s", root.absolutePath) }
    }

    /**
     * 递归覆盖复制；[relative] 是相对 PI 根的路径，仅用于判断哪些该跳过
     *
     * 跳过两类：整棵属于本路的目录（config 之类），以及已经存在、属于本路的动作配置 json
     */
    private fun copyInto(from: File, to: File, relative: String) {
        if (isSessionPath(relative)) return

        if (from.isDirectory) {
            to.mkdirs()
            from.listFiles()?.forEach { child -> copyInto(child, File(to, child.name), "$relative/${child.name}") }
            return
        }

        // 动作配置首次从包里落地当模板；之后这一路自己改过的，同步不再动它
        if (isActionConfig(relative) && to.isFile) return

        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        // 让目录里看得到的时间跟源一致，排查时少一层困惑
        to.setLastModified(from.lastModified())
    }

    private fun isSessionPath(relative: String): Boolean =
        relative.substringBefore('/') in SESSION_DIRS

    private fun isActionConfig(relative: String): Boolean =
        relative.startsWith(ACTION_CONFIG_PREFIX) && relative.endsWith(".json")

    /**
     * 源 PI 的同步标记 = 解包版本 + 被复制内容的内容指纹
     *
     * 加指纹是为了「直接改 PI 目录、不重新出包」这条用法：用户换了 `agent/` 里的脚本，
     * 这里能看出来，各实例下次启动自动同步过去，不必等 versionCode 变。只扫被复制的那几个
     * 条目（1MB 以内），不碰 resource 那 30MB
     */
    private fun readStamp(pi: File): String {
        val version = File(pi, PiInstaller.PI_MARKER_NAME).let { if (it.isFile) it.readText().trim() else "" }
        val fingerprint = StringBuilder()
        COPIED_ENTRIES.forEach { entry ->
            File(pi, entry).walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    fingerprint.append(file.absolutePath.substring(pi.absolutePath.length))
                        .append(':').append(file.length())
                        .append(':').append(file.lastModified())
                        .append('\n')
                }
        }
        return "$version|${fingerprint.toString().hashCode().toUInt().toString(16)}"
    }

    private fun readMarker(root: File): String? =
        File(root, PiInstaller.PI_MARKER_NAME).let { if (it.isFile) it.readText().trim() else null }

    private fun writeMarker(root: File, stamp: String) {
        runCatching { File(root, PiInstaller.PI_MARKER_NAME).writeText(stamp) }
            .onFailure { Timber.w(it, "SlotWorkspace: 写标记失败 %s", root.absolutePath) }
    }
}
