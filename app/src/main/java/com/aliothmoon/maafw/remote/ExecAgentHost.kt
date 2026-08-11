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
class ExecAgentHost(
    /** child 的每一行输出与它来自哪条流；默认丢弃，只有接了运行日志的调用点才传 */
    private val onOutput: (line: String, fromStderr: Boolean) -> Unit = { _, _ -> },
) : AgentHost {

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
        // argsPrefix 是构建期自己写的，认占位符；child_args 来自 PI，一律原样透传——
        // PI 作者可能就是要把花括号原样交给 agent，外壳不该替他解释
        val command = buildList {
            add(executable.absolutePath)
            entry.argsPrefix.mapTo(this) {
                it.resolveAgentPlaceholders(bundleDir, request.nativeLibraryDir)
            }
            addAll(request.agent.childArgs)
            add(request.identifier)
        }
        // 不合流：合了就分不出 agent 自己 print 的与加载器写的，两条各起一个泵
        val builder = ProcessBuilder(command)
            .directory(File(request.workingDir))
        entry.env.forEach { (key, value) ->
            builder.environment()[key] = value.resolveAgentPlaceholders(bundleDir, request.nativeLibraryDir)
        }

        Ln.i("ExecAgentHost: launching $command (cwd=${request.workingDir})")
        val process = runCatching { builder.start() }
            .getOrElse { throw AgentLaunchException("agent 启动失败：${it.message}", it) }
        return ProcessAgentSession(process, onOutput)
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
 * child 的两条流各起一个泵，同时转进 logcat 与 [onOutput]
 *
 * **两条都必须抽干**：Android 上进程没有可看的控制台，哪条不抽，child 写满那条的管道缓冲
 * 就会直接卡死。这也是当初合流的原因，但合流的代价是下游再也分不出话是谁说的
 *
 * logcat 那份留着不撤：[onOutput] 要过 binder，app 进程不在时那头没人接，而 child 起不来
 * 的现场恰恰常发生在那种时候
 */
private class ProcessAgentSession(
    private val process: Process,
    private val onOutput: (line: String, fromStderr: Boolean) -> Unit,
) : AgentSession {

    private val pumps = listOf(
        pump(process.inputStream, fromStderr = false),
        pump(process.errorStream, fromStderr = true),
    )

    private fun pump(stream: java.io.InputStream, fromStderr: Boolean): Thread {
        val tag = if (fromStderr) "agent!" else "agent|"
        return Thread({
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    Ln.i("$tag $line")
                    runCatching { onOutput(line, fromStderr) }
                        .onFailure { Ln.w("ExecAgentHost: agent output dispatch failed: ${it.message}") }
                }
            }
        }, if (fromStderr) "agent-err-pump" else "agent-out-pump").apply {
            isDaemon = true
            start()
        }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun close() {
        process.destroy()
        // agent 不响应 SIGTERM 时兜底：留着不管会占住 socket，下一轮 connect 撞上旧实例
        if (!process.waitFor(TERMINATE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
        pumps.forEach { it.interrupt() }
    }

    private companion object {
        const val TERMINATE_TIMEOUT_MILLIS = 2_000L
    }
}
