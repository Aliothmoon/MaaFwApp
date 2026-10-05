package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.runner.AgentExitCode
import com.aliothmoon.maafw.third.Ln
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 由特权进程 fork/exec 拉起 agent child
 *
 * child 与特权进程同 uid、同进程树，所以 env 直接继承、`TMPDIR` 天然一致，
 * AgentClient 与 AgentServer 算出同一个 socket 路径，传输与上游 MaaPiCli 逐字一致
 *
 * PI 的 `child_exec` / `child_args` 在这里都不参与拼命令：那两个字段是上游按桌面端整条命令行写的
 * （`uv run python x.py` 这种），设备上没有那个可执行体、PATH 上也没有解释器、PI 解包目录还是 noexec。
 * 只取前一半再接到别的解释器后面，拼出什么全看 PI 作者的桌面端习惯，所以整条命令由构建期的
 * `agent-runtime.json` 说了算（docs/pi-compatibility.md）
 *
 * PI 的 `agent[]` 只剩两个作用：声明有几个 agent（MaaFramework 按它建 client），
 * 以及错误消息里那个人看得懂的名字
 */
class ExecAgentHost(
    /** child 的每一行输出与它来自哪条流；默认丢弃，只有接了运行日志的调用点才传 */
    private val onOutput: (line: String, fromStderr: Boolean) -> Unit = { _, _ -> },
    /** child 没被要求退出却退了；它的两条流读完之后才调，现场的最后几行排在这之前 */
    private val onExit: (AgentExit) -> Unit = {},
    /** 现场文件的落点；日志目录到 `setup` 才知道，所以现取 */
    private val crashDir: () -> File? = { null },
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

        val wrapper = File(request.nativeLibraryDir, AGENT_EXEC)
        if (!wrapper.canExecute()) {
            throw AgentLaunchException("agent 启动包装不可执行：${wrapper.absolutePath}")
        }

        // identifier 恒在末位，对齐上游 Runner.cpp；它之前的部分全部来自 agent-runtime.json
        // PI 那边真正需要的参数由适配方一并写进 args——入口脚本本来就写在那儿了
        val command = buildList {
            add(wrapper.absolutePath)
            add(android.os.Process.myPid().toString())
            add(executable.absolutePath)
            entry.args.mapTo(this) {
                it.resolveAgentPlaceholders(bundleDir, request.nativeLibraryDir)
            }
            add(request.identifier)
        }
        // 不合流：合了就分不出 agent 自己 print 的与加载器写的，两条各起一个泵
        val builder = ProcessBuilder(command)
            .directory(File(request.workingDir))
        // PI_* 先落地：MaaFramework 版本只有这一侧问得到，app 侧算不出来，在这里补齐最后一项
        request.piEnv.forEach { (key, value) -> builder.environment()[key] = value }
        maaFrameworkVersion()?.let { builder.environment()["PI_CLIENT_MAAFW_VERSION"] = it }
        // 运行时描述后落地：那份是构建期人工写的，同名时按它来，留作本地调试的逃生舱
        entry.env.forEach { (key, value) ->
            builder.environment()[key] = value.resolveAgentPlaceholders(bundleDir, request.nativeLibraryDir)
        }

        Ln.i("ExecAgentHost: launching $command (cwd=${request.workingDir})")
        val process = runCatching { builder.start() }
            .getOrElse { throw AgentLaunchException("agent 启动失败：${it.message}", it) }
        val exec = executable.absolutePath
        return ProcessAgentSession(exec, process, onOutput) { pid, exitCode ->
            onExit(AgentExit(request.index, exec, exitCode, saveCrashReport(exec, pid, exitCode)))
        }
    }

    private fun saveCrashReport(executable: String, pid: Int?, exitCode: Int): String? {
        val signal = AgentExitCode.signalOf(exitCode) ?: return null
        if (pid == null || !AgentExitCode.leavesCrashDump(signal)) return null
        val dir = crashDir() ?: return null
        return AgentCrashReport.save(dir, executable, pid, exitCode)
    }

    /** 对齐 MXU：统一补 `v` 前缀，MaaVersion() 本身带不带都有可能 */
    private fun maaFrameworkVersion(): String? =
        MaaFrameworkLoader.library?.MaaVersion()?.takeIf(String::isNotBlank)
            ?.let { if (it.startsWith("v")) it else "v$it" }

    private fun readDescriptor(apkPath: String): AgentRuntimeDescriptor? = runCatching {
        AgentRuntimeDescriptor.readFromApk(apkPath)
    }.onFailure {
        Ln.e("ExecAgentHost: bad ${AgentRuntimeDescriptor.ASSET_PATH}: ${it.message}")
    }.getOrThrow()
}

private const val AGENT_EXEC = "libagentexec.so"

/**
 * CSI 转义序列（含 SGR 配色）
 * `\u001B` 由 Kotlin 在编译期解成字符，正则引擎拿到的是 ESC 本身，不依赖 ICU 对 `\uXXXX` 的支持
 */
private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")

/**
 * logcat 渲染不了颜色，转义符落在那儿只是噪音
 * 交给 onOutput 的那份**不剥**：agent 靠配色区分级别，UI 侧解析成文本样式
 */
private fun String.stripAnsiEscapes(): String =
    if (contains('\u001B')) replace(ANSI_ESCAPE, "") else this

/**
 * child 的两条流各起一个泵，同时转进 logcat 与 [onOutput]
 *
 * **两条都必须抽干**：Android 上进程没有可看的控制台，哪条不抽，child 写满那条的管道缓冲
 * 就会直接卡死。这也是当初合流的原因，但合流的代价是下游再也分不出话是谁说的
 *
 * logcat 那份留着不撤：[onOutput] 要过 binder，app 进程不在时那头没人接，而 child 起不来
 * 的现场恰恰常发生在那种时候
 */
internal class ProcessAgentSession(
    override val executable: String,
    private val process: Process,
    onOutput: (line: String, fromStderr: Boolean) -> Unit,
    onUnexpectedExit: (pid: Int?, exitCode: Int) -> Unit,
) : AgentSession {

    private val pid = process.pidOrNull()

    @Volatile
    private var exitExpected = false

    /** 单线程够用：定时冲洗只是把攒下的串交出去 */
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "agent-flush").apply { isDaemon = true }
    }

    // 每条流各攒各的，不共用一个批：混在一起就得像 MXU 那样标 "mixed"，
    // 而下游要靠这个字段决定配色，标成 mixed 等于又分不出来了
    private val batchers = listOf(false, true).map { fromStderr ->
        AgentOutputBatcher(fromStderr, flusher, onOutput)
    }

    private val pumps = listOf(
        pump(process.inputStream, fromStderr = false),
        pump(process.errorStream, fromStderr = true),
    )

    private fun pump(stream: java.io.InputStream, fromStderr: Boolean): Thread {
        val tag = if (fromStderr) "agent!" else "agent|"
        val batcher = batchers[if (fromStderr) 1 else 0]
        return Thread({
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    // 只有 logcat 这一份剥色，下游那份保留转义交给 UI 渲染
                    // logcat 那份逐行不批：它本来就是流水账，攒起来反而不好对时间
                    Ln.i("$tag ${line.stripAnsiEscapes()}")
                    batcher.add(line)
                }
            }
            // 读到 EOF 说明 child 的这条流已经关了，把最后攒的交出去——
            // 崩溃现场的最后几行 traceback 恰恰都在这里
            batcher.flush()
        }, if (fromStderr) "agent-err-pump" else "agent-out-pump").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 没人等 child 退出的话，它崩了要到下一次 custom 调用超时才露头，退出码也就此丢了
     *
     * 排在 [pumps] 之后建：上报前要等两条流读到 EOF，「已退出」那句才不会抢在 traceback 前面
     */
    init {
        Thread({
            val exitCode = runCatching { process.waitFor() }.getOrElse { return@Thread }
            if (exitExpected) return@Thread
            Ln.e("ExecAgentHost: agent exited unexpectedly, exec=$executable pid=$pid exitCode=$exitCode")
            pumps.forEach { pump -> runCatching { pump.join(PUMP_DRAIN_TIMEOUT_MILLIS) } }
            runCatching { onUnexpectedExit(pid, exitCode) }
                .onFailure { Ln.w("ExecAgentHost: agent exit dispatch failed: ${it.message}") }
        }, "agent-exit-watch").apply {
            isDaemon = true
            start()
        }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun expectExit() {
        exitExpected = true
    }

    override fun flushOutput() {
        batchers.forEach { it.flush() }
    }

    override fun close() {
        exitExpected = true
        process.destroy()
        // agent 不响应 SIGTERM 时兜底：留着不管会占住 socket，下一轮 connect 撞上旧实例
        if (!process.waitFor(TERMINATE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
        pumps.forEach { it.interrupt() }
        batchers.forEach { it.flush() }
        flusher.shutdownNow()
    }

    private companion object {
        const val TERMINATE_TIMEOUT_MILLIS = 2_000L

        /** child 已经没了，管道里剩的读完就是 EOF；孙进程还攥着管道时不能一直等 */
        const val PUMP_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

/**
 * `java.lang.Process` 在 Android 上不公开 pid。特权进程由 app_process 拉起、不受 hidden API 限制，
 * 直接读实现类的字段；读不到再从 `toString()` 的 `pid=` 里抠
 */
private fun Process.pidOrNull(): Int? =
    runCatching { javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(this) }.getOrNull()
        ?: PID_IN_TO_STRING.find(toString())?.groupValues?.get(1)?.toIntOrNull()

private val PID_IN_TO_STRING = Regex("pid=(\\d+)")

/**
 * 把同一瞬间涌出来的若干行攒成一次回调
 *
 * 省的是 binder 那一跳：child 一次 dump 上百行时，逐行发就是上百次 oneway 调用。
 * 桌面端 MXU 同样攒（`AgentOutputBatcher`），窗口取 1ms——不为省延迟，只为合并突发
 *
 * 窗口给到 [WINDOW_MILLIS] 而不是 1ms：过 binder 比进程内发事件贵，窗口太小攒不住；
 * 但也不能再大，日志里 agent 的话与 MaaFramework 的回调是交错着看的，攒久了顺序就乱了
 */
private class AgentOutputBatcher(
    private val fromStderr: Boolean,
    private val flusher: ScheduledExecutorService,
    private val sink: (line: String, fromStderr: Boolean) -> Unit,
) {

    private val lock = Any()
    private val pending = StringBuilder()
    private var lines = 0
    private var scheduled: ScheduledFuture<*>? = null

    fun add(line: String) {
        val ready = synchronized(lock) {
            if (lines > 0) pending.append('\n')
            pending.append(line)
            lines++
            when {
                // 攒够就立刻走，别让单次事务无限长大
                lines >= MAX_LINES -> takeLocked()
                else -> {
                    if (scheduled == null) {
                        scheduled = runCatching {
                            flusher.schedule({ flush() }, WINDOW_MILLIS, TimeUnit.MILLISECONDS)
                        }.getOrNull()
                    }
                    null
                }
            }
        }
        emit(ready)
    }

    fun flush() = emit(synchronized(lock) { takeLocked() })

    /** 必须在锁外发：这一步要过 binder，占着锁会把泵线程也堵上 */
    private fun emit(batch: String?) {
        if (batch == null) return
        runCatching { sink(batch, fromStderr) }
            .onFailure { Ln.w("ExecAgentHost: agent output dispatch failed: ${it.message}") }
    }

    private fun takeLocked(): String? {
        scheduled?.cancel(false)
        scheduled = null
        if (lines == 0) return null
        val batch = pending.toString()
        pending.setLength(0)
        lines = 0
        return batch
    }

    private companion object {
        const val WINDOW_MILLIS = 30L
        const val MAX_LINES = 64
    }
}
