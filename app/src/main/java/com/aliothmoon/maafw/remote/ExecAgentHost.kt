package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.util.zip.ZipFile

/**
 * 由特权进程 fork/exec 拉起 agent child
 *
 * child 与特权进程同 uid、同进程树，所以 env 直接继承、`TMPDIR` 天然一致，
 * AgentClient 与 AgentServer 算出同一个 socket 路径，传输与上游 MaaPiCli 逐字一致
 *
 * PI 的 `child_exec` 在这里不解释也不执行：设备 PATH 上没有解释器，PI 解包目录又是 noexec；
 * 真正拉起哪个可执行体由构建期的 `agent-runtime.json` 决定（docs/pi-compatibility.md）
 */
class ExecAgentHost : AgentHost {

    override fun launch(request: AgentLaunchRequest): AgentSession {
        val descriptor = readDescriptor(request.apkPath)
            ?: throw AgentLaunchException(
                "本包未带 agent 运行时：PI 声明了 ${request.agent.childExec}，" +
                    "但 assets 里没有 ${AgentRuntimeDescriptor.ASSET_PATH}",
            )
        val entry = descriptor.runtimes.getOrNull(request.index)
            ?: throw AgentLaunchException(
                "agent 运行时数量不足：PI 第 ${request.index + 1} 个 agent 无对应条目" +
                    "（描述里共 ${descriptor.runtimes.size} 条）",
            )

        val bundleDir = when (entry.location) {
            AgentRuntimeLocation.BUNDLE -> AgentInstaller(request.apkPath).ensureInstalled().absolutePath
            AgentRuntimeLocation.NATIVE_LIBS -> null
        }
        val root = bundleDir ?: request.nativeLibraryDir
        val executable = File(root, entry.executable)
        if (!executable.canExecute()) {
            throw AgentLaunchException("agent 可执行体不可执行：${executable.absolutePath}")
        }

        // 顺序对齐上游 Runner.cpp：child_args 之后追加 identifier，identifier 恒在末位
        val command = buildList {
            add(executable.absolutePath)
            addAll(entry.argsPrefix)
            addAll(request.agent.childArgs)
            add(request.identifier)
        }
        val builder = ProcessBuilder(command)
            .directory(File(request.workingDir))
            .redirectErrorStream(true)
        entry.env.forEach { (key, value) ->
            builder.environment()[key] = value.resolveAgentPlaceholders(bundleDir, request.nativeLibraryDir)
        }

        Ln.i("ExecAgentHost: launching $command (cwd=${request.workingDir})")
        val process = runCatching { builder.start() }
            .getOrElse { throw AgentLaunchException("agent 启动失败：${it.message}", it) }
        return ProcessAgentSession(process)
    }

    private fun readDescriptor(apkPath: String): AgentRuntimeDescriptor? = runCatching {
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("assets/${AgentRuntimeDescriptor.ASSET_PATH}") ?: return null
            val content = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            AgentRuntimeDescriptor.parse(content)
        }
    }.onFailure {
        Ln.e("ExecAgentHost: bad ${AgentRuntimeDescriptor.ASSET_PATH}: ${it.message}")
    }.getOrThrow()
}

/**
 * child 的 stdout/stderr 合流后转进 logcat：Android 上进程没有可看的控制台，
 * 不抽干这条管道，child 写满管道缓冲后会直接卡死
 */
private class ProcessAgentSession(private val process: Process) : AgentSession {

    private val pump = Thread({
        runCatching {
            process.inputStream.bufferedReader().forEachLine { Ln.i("agent| $it") }
        }
    }, "agent-log-pump").apply {
        isDaemon = true
        start()
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun close() {
        process.destroy()
        // agent 不响应 SIGTERM 时兜底：留着不管会占住 socket，下一轮 connect 撞上旧实例
        if (!process.waitFor(TERMINATE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
        pump.interrupt()
    }

    private companion object {
        const val TERMINATE_TIMEOUT_MILLIS = 2_000L
    }
}
