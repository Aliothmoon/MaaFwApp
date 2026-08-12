package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.maa.MaaAgentClientLibrary
import com.aliothmoon.maafw.maa.MaaAgentClientLoader
import com.aliothmoon.maafw.maa.MaaFrameworkLibrary
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.maa.MaaGlobalOption
import com.aliothmoon.maafw.maa.MaaLoggingLevel
import com.aliothmoon.maafw.maa.MaaStatus
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.runner.AgentPayload
import com.aliothmoon.maafw.runner.RunOutcome
import com.aliothmoon.maafw.runner.RunPlanPayload
import com.aliothmoon.maafw.runner.runPlanWireJson
import com.aliothmoon.maafw.third.Ln
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 特权进程内的 MaaFramework 执行器
 *
 * native handle 全部只存在于这里；app 侧只经 binder 拿事件与结果
 * 单工作线程串行：MaaFramework 的一个 Tasker 同时只跑一轮
 */
class MaaRunner(private val agentHost: AgentHost) {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "maa-runner").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private val callbackRef = AtomicReference<IMaaRunnerCallback?>()

    // native handle
    private var resource: Pointer? = null
    private var controller: Pointer? = null
    private var tasker: Pointer? = null

    /** 已构建的 resource 对应的路径；变了就重建 */
    private var loadedResourcePaths: List<String> = emptyList()

    /**
     * 已建 controller 绑定的 display_id；变了必须重建
     *
     * 光判 `MaaControllerConnected` 不够：切运行模式后旧 controller 仍报 connected，
     * 但它的 display_id 指向的屏已经销毁了，`start_app` 会拿着废 id 去 launchDisplayId
     * 而被系统拒（`SecurityException: Permission Denial ... with launchDisplayId=<旧 id>`）
     */
    private var boundDisplayId: Int? = null

    /** agent child 的 cwd，对齐上游 MaaPiCli 的 `agent.cwd = resource_dir_` */
    private var projectRoot: String? = null

    /** 与 resource 同生命周期：client 绑在 resource 上，resource 重建则整批重来 */
    private var agents: List<ActiveAgent> = emptyList()
    private var loadedAgents: List<AgentPayload> = emptyList()

    private class ActiveAgent(val client: Pointer, val session: AgentSession)

    fun setProjectRoot(path: String) {
        projectRoot = path
    }

    /** JNA 回调必须被强引用住，否则会被 GC，native 回调时踩空 */
    private val eventSink = MaaFrameworkLibrary.MaaEventCallback { _, message, detailsJson, _ ->
        Ln.i("MaaEventCallback on $message")
        runCatching {
            callbackRef.get()?.onEvent(message.orEmpty(), detailsJson.orEmpty())
        }.onFailure {
            // 回调穿回 native 会直接崩进程
            Ln.w("MaaRunner: event dispatch failed: ${it.message}")
        }
    }

    fun setCallback(callback: IMaaRunnerCallback?) {
        callbackRef.set(callback)
    }

    /** agent child 的一行输出；由 [AgentHost] 的泵线程调用，app 侧不在时静默丢弃 */
    fun onAgentLine(line: String, fromStderr: Boolean) {
        notify { onAgentOutput(line, fromStderr) }
    }

    /**
     * 把 controller 手里那张缓存帧落到 [path]，供 focus 模板的 `{image}` 用
     *
     * 走文件而不是把字节回传：一张 720p PNG 动辄几百 KB，binder 事务缓冲总共才 1MB，
     * 直接传是在赌。落点由 app 侧给，它挑的是双方都读得到的外部私有目录
     */
    fun saveCachedImage(path: String): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        val ctrl = controller ?: return false
        val buffer = lib.MaaImageBufferCreate() ?: return false
        return try {
            if (lib.MaaControllerCachedImage(ctrl, buffer).toInt() == 0) return false
            if (lib.MaaImageBufferIsEmpty(buffer).toInt() != 0) return false
            val size = lib.MaaImageBufferGetEncodedSize(buffer)
            if (size <= 0) return false
            val data = lib.MaaImageBufferGetEncoded(buffer) ?: return false
            val bytes = data.getByteArray(0, size.toInt())
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (e: Throwable) {
            Ln.w("MaaRunner: saveCachedImage failed: ${e.message}")
            false
        } finally {
            lib.MaaImageBufferDestroy(buffer)
        }
    }

    /**
     * 全局选项是进程级单例，setup 时设一次即可
     *
     * 不设 LOG_DIR 时 MaaFramework 按进程 CWD 解析 `maa.log` 与 Screencap 动作的落点，
     * 特权进程的 CWD 不可写，Screencap 会直接失败
     */
    fun applyGlobalOptions(logDir: String, debug: Boolean) {
        val lib = MaaFrameworkLoader.library ?: return
        if (!setStringOption(lib, MaaGlobalOption.LOG_DIR, logDir)) {
            Ln.w("MaaRunner: set LOG_DIR failed: $logDir")
        }
        setIntOption(
            lib,
            MaaGlobalOption.STDOUT_LEVEL,
            if (debug) MaaLoggingLevel.INFO else MaaLoggingLevel.ERROR,
        )
        // 节点出错时自动存一张现场图，比事后复现便宜；SAVE_DRAW 会每次识别都写盘，暂不开
        setBoolOption(lib, MaaGlobalOption.SAVE_ON_ERROR, debug)
        Ln.i("MaaRunner: global options applied, logDir=$logDir debug=$debug")
    }

    private fun setStringOption(lib: MaaFrameworkLibrary, key: Int, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        // MaaFramework 按 val_size 截取，不看结尾 NUL；Memory 不能申请 0 字节
        val memory = Memory(bytes.size.coerceAtLeast(1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        return lib.MaaGlobalSetOption(key, memory, bytes.size.toLong()).toInt() != 0
    }

    private fun setIntOption(lib: MaaFrameworkLibrary, key: Int, value: Int): Boolean {
        val memory = Memory(Int.SIZE_BYTES.toLong())
        memory.setInt(0, value)
        return lib.MaaGlobalSetOption(key, memory, Int.SIZE_BYTES.toLong()).toInt() != 0
    }

    private fun setBoolOption(lib: MaaFrameworkLibrary, key: Int, value: Boolean): Boolean {
        val memory = Memory(1)
        memory.setByte(0, if (value) 1 else 0)
        return lib.MaaGlobalSetOption(key, memory, 1).toInt() != 0
    }

    fun isRunning(): Boolean = running.get()

    /** 立即返回；执行进度与结果走 [IMaaRunnerCallback] */
    fun start(payloadJson: String): Boolean {
        if (!running.compareAndSet(false, true)) {
            Ln.w("MaaRunner: already running")
            return false
        }
        val payload = runCatching { runPlanWireJson.decodeFromString<RunPlanPayload>(payloadJson) }
            .getOrElse {
                running.set(false)
                Ln.e("MaaRunner: bad payload: ${it.message}")
                return false
            }
        worker.execute { runPlan(payload) }
        return true
    }

    /** 幂等：未在跑时也返回 true，避免 app 侧为了停止先查状态 */
    fun stop(): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        val handle = tasker ?: return true
        lib.MaaTaskerPostStop(handle)
        return true
    }

    fun destroy() {
        worker.shutdownNow()
        releaseNative()
    }

    private fun runPlan(payload: RunPlanPayload) {
        var outcome = RunOutcome.FAILED
        var reason = ""
        try {
            val lib = MaaFrameworkLoader.library
            if (lib == null) {
                reason = "libMaaFramework.so 加载失败"
                return
            }
            val prepared = prepare(lib, payload)
            if (prepared != null) {
                reason = prepared
                return
            }

            var anyFailed = false
            var cancelled = false
            payload.tasks.forEachIndexed { index, task ->
                if (cancelled) return@forEachIndexed
                notify { onTaskStarted(task.taskName, index, payload.tasks.size) }

                val overrides = JsonArray(task.pipelineOverrides).toString()
                val taskId = lib.MaaTaskerPostTask(tasker, task.entry, overrides)
                if (taskId == INVALID_ID) {
                    anyFailed = true
                    notify { onTaskFinished(task.taskName, false, "PostTask 被拒绝") }
                    return@forEachIndexed
                }
                val status = lib.MaaTaskerWait(tasker, taskId)
                val success = status == MaaStatus.SUCCEEDED
                if (!success) anyFailed = true
                notify { onTaskFinished(task.taskName, success, statusText(status)) }

                // Stop 之后 Tasker 会把剩余任务直接判失败，这里提前收尾避免刷一串假失败
                if (lib.MaaTaskerStopping(tasker).toInt() != 0) {
                    cancelled = true
                }
            }

            outcome = when {
                cancelled -> RunOutcome.CANCELLED
                anyFailed -> RunOutcome.COMPLETED_WITH_FAILURES
                else -> RunOutcome.COMPLETED
            }
        } catch (e: Throwable) {
            reason = "${e.javaClass.simpleName}: ${e.message}"
            Ln.e("MaaRunner: run failed: $reason")
        } finally {
            running.set(false)
            notify { onFinished(outcome, reason) }
        }
    }

    /** 返回 null 表示就绪，否则返回失败原因 */
    private fun prepare(lib: MaaFrameworkLibrary, payload: RunPlanPayload): String? {
        // bridge 必须先 System.loadLibrary 进本进程，控制单元按名 dlopen 才能命中同一份
        if (!NativeBridgeLib.LOADED) {
            return "libbridge.so 未加载，无法建立 native controller"
        }

        val displayId = when (payload.displayMode) {
            DisplayMode.PRIMARY ->
                if (PrimaryDisplayManager.getCaptureSize() == null) {
                    return "主屏采集未启动"
                } else {
                    PrimaryDisplayManager.DISPLAY_ID
                }

            else -> VirtualDisplayManager.getDisplayId().takeIf {
                it != DefaultDisplayConfig.DISPLAY_NONE
            } ?: return "虚拟显示器未启动"
        }

        if (resource == null || loadedResourcePaths != payload.resourcePaths) {
            releaseResource(lib)
            val res = lib.MaaResourceCreate() ?: return "MaaResourceCreate 失败"
            lib.MaaResourceAddSink(res, eventSink, null)
            payload.resourcePaths.forEach { path ->
                val id = lib.MaaResourcePostBundle(res, path)
                if (id == INVALID_ID || lib.MaaResourceWait(res, id) != MaaStatus.SUCCEEDED) {
                    lib.MaaResourceDestroy(res)
                    return "资源加载失败: $path"
                }
            }
            resource = res
            loadedResourcePaths = payload.resourcePaths
            // 资源换了，绑定关系也得重来
            releaseTasker(lib)
        }

        prepareAgents(lib, payload)?.let { return it }

        if (controller == null ||
            boundDisplayId != displayId ||
            lib.MaaControllerConnected(controller).toInt() == 0
        ) {
            releaseController(lib)
            val config = buildControllerConfig(payload, displayId)
            val ctrl = lib.MaaAndroidNativeControllerCreate(config)
                ?: return "MaaAndroidNativeControllerCreate 失败: $config"
            lib.MaaControllerAddSink(ctrl, eventSink, null)
            val ctrlId = lib.MaaControllerPostConnection(ctrl)
            if (ctrlId == INVALID_ID || lib.MaaControllerWait(
                    ctrl,
                    ctrlId
                ) != MaaStatus.SUCCEEDED
            ) {
                lib.MaaControllerDestroy(ctrl)
                return "controller 连接失败"
            }
            controller = ctrl
            boundDisplayId = displayId
            releaseTasker(lib)
        }

        if (tasker == null) {
            val tsk = lib.MaaTaskerCreate() ?: return "MaaTaskerCreate 失败"
            lib.MaaTaskerAddSink(tsk, eventSink, null)
            if (lib.MaaTaskerBindResource(tsk, resource).toInt() == 0 ||
                lib.MaaTaskerBindController(tsk, controller).toInt() == 0 ||
                lib.MaaTaskerInited(tsk).toInt() == 0
            ) {
                lib.MaaTaskerDestroy(tsk)
                return "Tasker 绑定失败"
            }
            tasker = tsk
        }
        return null
    }

    /**
     * PI 声明的 agent：建 client → 绑 resource → 读回 identifier → 拉起 child → connect
     * 返回 null 表示就绪（含「本次无 agent」），否则返回失败原因
     *
     * client 绑在 resource 上，所以整批与 resource 同生命周期；child 死了也要连 client 一起重来，
     * 光重起 child 连不回已经 bind 在旧 socket 上的 client
     */
    private fun prepareAgents(lib: MaaFrameworkLibrary, payload: RunPlanPayload): String? {
        if (payload.agents.isEmpty()) {
            releaseAgents()
            return null
        }
        val agentLib = MaaAgentClientLoader.library
            ?: return "libMaaAgentClient.so 加载失败，无法拉起 agent"

        val reusable = loadedAgents == payload.agents &&
            agents.size == payload.agents.size &&
            agents.all { it.session.isAlive() && agentLib.MaaAgentClientAlive(it.client).toInt() != 0 }
        if (reusable) return null

        releaseAgents()

        val workingDir = projectRoot ?: return "PI 根未就绪，agent 无法确定工作目录"
        val started = mutableListOf<ActiveAgent>()
        payload.agents.forEachIndexed { index, agent ->
            val client = agentLib.MaaAgentClientCreateTcp(AUTO_PORT)
                ?: return failAgents(started, "MaaAgentClientCreateTcp 失败")
            if (agentLib.MaaAgentClientBindResource(client, resource).toInt() == 0) {
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, "agent 绑定 resource 失败")
            }
            val identifier = readIdentifier(lib, agentLib, client)
                ?: run {
                    agentLib.MaaAgentClientDestroy(client)
                    return failAgents(started, "读取 agent identifier 失败")
                }
            agentLib.MaaAgentClientSetTimeout(client, AGENT_CONNECT_TIMEOUT_MILLIS)

            val session = try {
                agentHost.launch(
                    AgentLaunchRequest(
                        index = index,
                        agent = agent,
                        identifier = identifier,
                        apkPath = payload.apkPath,
                        nativeLibraryDir = payload.nativeLibraryDir,
                        workingDir = workingDir,
                        piEnv = payload.piEnv,
                    ),
                )
            } catch (e: AgentLaunchException) {
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, e.message.orEmpty())
            }

            if (agentLib.MaaAgentClientConnect(client).toInt() == 0) {
                session.close()
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, "agent 连接超时：${agent.childExec}")
            }
            Ln.i("MaaRunner: agent[$index] connected, identifier=$identifier")
            started += ActiveAgent(client, session)
        }

        agents = started
        loadedAgents = payload.agents
        // agent 注册的 custom 节点挂在 resource 上，绑定关系要重来
        releaseTasker(lib)
        return null
    }

    /** identifier 走 MaaStringBuffer 出参；buffer 由调用方负责销毁 */
    private fun readIdentifier(
        lib: MaaFrameworkLibrary,
        agentLib: MaaAgentClientLibrary,
        client: Pointer,
    ): String? {
        val buffer = lib.MaaStringBufferCreate() ?: return null
        return try {
            if (agentLib.MaaAgentClientIdentifier(client, buffer).toInt() == 0) null
            else lib.MaaStringBufferGet(buffer)?.takeIf(String::isNotEmpty)
        } finally {
            lib.MaaStringBufferDestroy(buffer)
        }
    }

    private fun failAgents(started: List<ActiveAgent>, reason: String): String {
        started.forEach { releaseAgent(it) }
        return reason
    }

    private fun releaseAgents() {
        agents.forEach { releaseAgent(it) }
        agents = emptyList()
        loadedAgents = emptyList()
    }

    private fun releaseAgent(agent: ActiveAgent) {
        val agentLib = MaaAgentClientLoader.library
        runCatching { agentLib?.MaaAgentClientDisconnect(agent.client) }
        runCatching { agent.session.close() }
        runCatching { agentLib?.MaaAgentClientDestroy(agent.client) }
    }

    /**
     * screen_resolution 必须与帧缓冲、触摸坐标空间三者一致，不一致时 screencap 立即失败
     * library_path 用裸名：bridge 已在本进程加载，控制单元 dlopen 同名即命中同一份
     *
     * 虚拟屏模式下 force_stop 必须为 true：目标应用若已在主屏上跑着，startActivity 会复用它在主屏的
     * 既有 task，虚拟屏上拿不到画面。先杀掉再拉起，进程才会落到虚拟屏上
     * 主屏模式反过来——目标就在主屏，杀掉只会把用户已经摆好的现场清空
     */
    private fun buildControllerConfig(payload: RunPlanPayload, displayId: Int): String {
        val (width, height) = when (payload.displayMode) {
            // 主屏尺寸跟着旋转变，app 侧发 payload 时算的值可能已经过期，只认采集器当下这一份
            DisplayMode.PRIMARY -> PrimaryDisplayManager.getCaptureSize()
                ?: (DefaultDisplayConfig.WIDTH to DefaultDisplayConfig.HEIGHT)

            else -> {
                val vd = VirtualDisplayManager.getConfig()
                (payload.screenWidth.takeIf { it > 0 } ?: vd.width) to
                    (payload.screenHeight.takeIf { it > 0 } ?: vd.height)
            }
        }
        return buildJsonObject {
            put("library_path", BRIDGE_LIBRARY_NAME)
            put("screen_resolution", buildJsonObject {
                put("width", width)
                put("height", height)
            })
            put("display_id", displayId)
            put("force_stop", payload.displayMode != DisplayMode.PRIMARY)
        }.toString()
    }

    private fun releaseNative() {
        val lib = MaaFrameworkLoader.library ?: return
        releaseTasker(lib)
        releaseController(lib)
        releaseResource(lib)
    }


    private fun releaseTasker(lib: MaaFrameworkLibrary) {
        tasker?.let(lib::MaaTaskerDestroy)
        tasker = null
    }

    private fun releaseController(lib: MaaFrameworkLibrary) {
        controller?.let(lib::MaaControllerDestroy)
        controller = null
        boundDisplayId = null
    }

    /** agent client 绑在 resource 上，销毁 resource 前必须先把 client 与 child 收掉 */
    private fun releaseResource(lib: MaaFrameworkLibrary) {
        releaseAgents()
        resource?.let(lib::MaaResourceDestroy)
        resource = null
        loadedResourcePaths = emptyList()
    }

    private inline fun notify(block: IMaaRunnerCallback.() -> Unit) {
        val callback = callbackRef.get() ?: return
        runCatching { callback.block() }
            .onFailure { Ln.w("MaaRunner: callback failed: ${it.message}") }
    }

    private fun statusText(status: Int): String = when (status) {
        MaaStatus.SUCCEEDED -> "succeeded"
        MaaStatus.FAILED -> "failed"
        MaaStatus.RUNNING -> "running"
        MaaStatus.PENDING -> "pending"
        else -> "invalid($status)"
    }

    private companion object {
        /** MaaInvalidId */
        const val INVALID_ID = 0L
        const val BRIDGE_LIBRARY_NAME = "libbridge.so"

        /** 解释器这类 child 冷启动要几秒，超时给宽一点；连不上会整批任务失败，宁可多等 */
        const val AGENT_CONNECT_TIMEOUT_MILLIS = 30_000L

        /**
         * 让系统分配回环端口；identifier 随即变成实际端口，原样传给 child
         *
         * 代价是同机任何带 INTERNET 权限的应用都能连上这个端口冒充 agent；
         * 但 unix socket 那条在 shell 域下建不出 sock_file，没有别的选择
         */
        const val AUTO_PORT: Short = 0
    }
}
