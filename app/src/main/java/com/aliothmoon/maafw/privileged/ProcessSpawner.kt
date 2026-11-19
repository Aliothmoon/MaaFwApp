package com.aliothmoon.maafw.privileged


import timber.log.Timber
/** 提权进程的拉起方式（su / Shizuku newProcess），与服务规格解耦 */
interface ProcessSpawner {

    /** 包成完整 shell 命令：后台化（退出状态丢）或前台 exec（可探活） */
    fun wrapCommand(launcherPath: String, invocation: String): String

    /** 执行拉起，失败抛异常；返回探活句柄，拉起方式不支持探活时返回 null */
    fun spawn(command: String): SpawnHandle?

    /** 同步跑完一条命令再返回 stdout，避免与下一次拉起交错；失败抛异常 */
    fun run(command: String): List<String>

    fun killResidual(processName: String) {
        runCatching { run(killByNameCommand(processName)) }
            .onFailure { Timber.w(it, "kill residual %s failed", processName) }
    }

    /** 同名进程及其已运行时长，查不了返回空 */
    fun findProcesses(processName: String): List<ResidualProcess> =
        runCatching { parseProcesses(run(findProcessesCommand(processName))) }
            .onFailure { Timber.w(it, "find %s failed", processName) }
            .getOrDefault(emptyList())

    /** 按 pid 硬杀：被 SIGSTOP 停住的也杀得掉 */
    fun forceKill(pids: List<Int>) {
        runCatching { run(forceKillCommand(pids)) }
            .onFailure { Timber.w(it, "force kill %s failed", pids) }
    }
}

data class ResidualProcess(val pid: Int, val elapsed: String)

interface SpawnHandle {

    fun isAlive(): Boolean

    fun exitCode(): Int?
}

class ProcessExitedException(val exitCode: Int?) :
    IllegalStateException("launcher exited early code=${exitCode ?: "?"}")

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

/**
 * root 执行时把 [dir] 下不归 shell 的条目修回 2000：root 建的目录降到 shell 后写不进去
 * 以 shell 身份执行时没权限也用不着，直接跳过；`-h` 改链接本身，不跟进链接指向的文件
 */
internal fun shellOwnershipRepairCommand(dir: String): String =
    $$"[ \"$(id -u)\" = 0 ] && find $${shellQuote(dir)} ! -user 2000 -exec chown -h 2000:2000 {} + 2>/dev/null"

/** SIGTERM 触发服务进程 shutdown hook 优雅清理 */
internal fun killByNameCommand(processName: String): String =
    "kill $(pidof ${shellQuote(processName)}) 2>/dev/null"

/** 没有同名进程时 pidof 失败、无输出 */
internal fun findProcessesCommand(processName: String): String =
    "p=$(pidof ${shellQuote(processName)}) && ps -o PID,ETIME -p \${p// /,}"

/** 跳过表头，取 PID 与 ETIME 两列 */
internal fun parseProcesses(lines: List<String>): List<ResidualProcess> = lines.mapNotNull { line ->
    val cols = line.trim().split(Regex("\\s+"))
    val pid = cols.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
    ResidualProcess(pid, cols.getOrNull(1).orEmpty())
}

internal fun forceKillCommand(pids: List<Int>): String = "kill -9 ${pids.joinToString(" ")} 2>/dev/null"
