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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 特权进程内的 MaaFramework 执行器
 *
 * native handle 全部只存在于这里；app 侧只经 binder 拿事件与结果
 * 单工作线程串行：MaaFramework 的一个 Tasker 同时只跑一轮
 */
class MaaRunner(private val agentHost: AgentHost) {

    // agent child 由这条线程 fork，PDEATHSIG 认线程：线程被换掉时 child 一并收走，下一轮判不可复用重拉
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "maa-runner").apply { isDaemon = true }
    }

    /** Binder stop can arrive while the worker is still preparing native handles. */
    private val lifecycleLock = Any()
    private var running = false
    private var stopRequested = false
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

    /**
     * 与 resource 同生命周期：client 绑在 resource 上，resource 重建则整批重来
     * 另外每个 client 还登记着当前的 resource / controller / tasker（见 [registerAgentSinks]）
     */
    private var agents: List<ActiveAgent> = emptyList()
    private var loadedAgents: List<AgentPayload> = emptyList()

    /** app 侧经 binder 现读设置后设置；不设时框架核心默认 false，出错不存现场图 */
    @Volatile
    private var saveOnError = true

    private class ActiveAgent(val client: Pointer, val session: AgentSession) {
        /** 这个 client 当前登记着的对象；null 表示还没登记 */
        var sinks: SinkTargets? = null
    }

    /** JNA Pointer 按地址判等 */
    private data class SinkTargets(val resource: Pointer, val controller: Pointer, val tasker: Pointer)

    /** 换 controller 时退下来的旧对象，等 agent 改登记到新对象上之后再拆 */
    private class RetiredHandles(val controller: Pointer?, val tasker: Pointer?)

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

    /** 由 [AgentHost] 等 child 退出的那条线程调用；不在这里重拉，下一轮 [prepareAgents] 见它死了自会整批重来 */
    fun onAgentExited(exit: AgentExit) {
        notify { onAgentExited(exit.index, exit.executable, exit.exitCode, exit.crashReport) }
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
        setBoolOption(lib, MaaGlobalOption.SAVE_ON_ERROR, saveOnError)
        Ln.i("MaaRunner: global options applied, logDir=$logDir debug=$debug")
    }

    /** binder 途径独立更新；立即生效，PipelineTask 每次出错时现读这个进程级单例 */
    fun setSaveOnError(enabled: Boolean) {
        saveOnError = enabled
        MaaFrameworkLoader.library?.let { setBoolOption(it, MaaGlobalOption.SAVE_ON_ERROR, enabled) }
        Ln.i("MaaRunner: saveOnError=$enabled")
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

    fun isRunning(): Boolean = synchronized(lifecycleLock) { running }

    /** 立即返回；执行进度与结果走 [IMaaRunnerCallback] */
    fun start(payloadJson: String): Boolean {
        synchronized(lifecycleLock) {
            if (running) {
                Ln.w("MaaRunner: already running")
                return false
            }
            running = true
            stopRequested = false
        }
        val payload = runCatching { runPlanWireJson.decodeFromString<RunPlanPayload>(payloadJson) }
            .getOrElse {
                synchronized(lifecycleLock) { running = false }
                Ln.e("MaaRunner: bad payload: ${it.message}")
                return false
            }
        worker.execute { runPlan(payload) }
        return true
    }

    /** 幂等：未在跑时也返回 true，避免 app 侧为了停止先查状态 */
    fun stop(): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        synchronized(lifecycleLock) {
            if (!running) return true
            stopRequested = true
            tasker?.let(lib::MaaTaskerPostStop)
        }
        return true
    }

    fun destroy() {
        // 跑着的那轮先叫停，worker 才腾得出手来拆
        stop()
        // 拆 native 排到 worker 上：handle 只归它管，而且它一退 child 就被 PDEATHSIG 收走，得拆完再停
        runCatching {
            worker.submit { releaseNative() }.get(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.onFailure {
            // 那一轮停不下来就不拆了：进程随后就退，child 跟着 PDEATHSIG 走
            Ln.w("MaaRunner: native release skipped on destroy: $it")
        }
        worker.shutdownNow()
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
                if (isStopRequested()) {
                    outcome = RunOutcome.CANCELLED
                    reason = ""
                }
                return
            }

            var anyFailed = false
            var cancelled = false
            payload.tasks.forEachIndexed { index, task ->
                if (cancelled) return@forEachIndexed
                if (stopRequested(lib)) {
                    cancelled = true
                    return@forEachIndexed
                }
                notify { onTaskStarted(task.taskName, index, payload.tasks.size) }

                val overrides = JsonArray(task.pipelineOverrides).toString()
                val currentTasker = synchronized(lifecycleLock) { tasker }
                val taskId = lib.MaaTaskerPostTask(currentTasker, task.entry, overrides)
                if (taskId == INVALID_ID) {
                    anyFailed = true
                    notify { onTaskFinished(task.taskName, false, "PostTask 被拒绝") }
                    return@forEachIndexed
                }
                val status = lib.MaaTaskerWait(currentTasker, taskId)
                val success = status == MaaStatus.SUCCEEDED
                if (!success) anyFailed = true
                notify { onTaskFinished(task.taskName, success, statusText(status)) }

                // Stop 之后 Tasker 会把剩余任务直接判失败，这里提前收尾避免刷一串假失败
                if (lib.MaaTaskerStopping(currentTasker).toInt() != 0) {
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
            synchronized(lifecycleLock) {
                running = false
                stopRequested = false
            }
            // agent 输出按窗口攒批，晚于终局到 app 就进不了这一轮的日志。比如 go-service 在任务开始时
            // 查出分辨率不对，先打警告再 PostStop，警告还攒在批里这一轮就收尾了。同一线程先交，顺序不会乱
            agents.forEach { runCatching { it.session.flushOutput() } }
            notify { onFinished(outcome, reason) }
        }
    }

    private fun isStopRequested(): Boolean = synchronized(lifecycleLock) {
        running && stopRequested
    }

    private fun stopRequested(lib: MaaFrameworkLibrary): Boolean {
        val handle = synchronized(lifecycleLock) {
            if (!running || !stopRequested) return false
            tasker
        }
        if (handle != null) lib.MaaTaskerPostStop(handle)
        return true
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
        }

        prepareAgents(lib, payload)?.let { return it }

        // 换 controller 时先建新的、把 agent 改登记过去，再拆旧的 controller 与 tasker：
        // agent client 断开与销毁时还要对登记过的对象 remove_sink，旧对象先拆就成了野指针；
        // 这样换屏时活着的 agent 照常复用，不必重拉 child
        var retired: RetiredHandles? = null
        if (controller == null ||
            boundDisplayId != displayId ||
            lib.MaaControllerConnected(controller).toInt() == 0
        ) {
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
            // Tasker 手里是旧 controller 的裸指针，跟着一起退
            retired = RetiredHandles(controller, takeTasker())
            controller = ctrl
            boundDisplayId = displayId
        }

        try {
            val needsTasker = synchronized(lifecycleLock) { tasker == null }
            if (needsTasker) {
                val tsk = lib.MaaTaskerCreate() ?: return "MaaTaskerCreate 失败"
                lib.MaaTaskerAddSink(tsk, eventSink, null)
                // tasker sink 只收 Tasker.Task.*；Node.* 连同 focus 模板都走 context sink
                lib.MaaTaskerAddContextSink(tsk, eventSink, null)
                if (lib.MaaTaskerBindResource(tsk, resource).toInt() == 0 ||
                    lib.MaaTaskerBindController(tsk, controller).toInt() == 0 ||
                    lib.MaaTaskerInited(tsk).toInt() == 0
                ) {
                    lib.MaaTaskerDestroy(tsk)
                    return "Tasker 绑定失败"
                }
                synchronized(lifecycleLock) { tasker = tsk }
            }
            return registerAgentSinks()
        } finally {
            // 没能改登记过去的（建 tasker 或登记失败），由 destroyTasker / destroyController 先收掉 agent
            retired?.let {
                it.tasker?.let { handle -> destroyTasker(lib, handle) }
                it.controller?.let { handle -> destroyController(lib, handle) }
            }
        }
    }

    /**
     * 每个 agent 都登记当前这套 resource / controller / tasker，agent 侧 `AgentServerAdd*Sink` 的监听器
     * （比如 MaaEnd go-service 在任务开始前查分辨率）才收得到事件；对齐 MXU 在 connect 之后的 register_sinks
     *
     * client 只记裸指针，再次登记、Disconnect、Destroy 都会对上一次登记的对象 remove_sink，
     * 所以登记过的对象必须活得比登记久：换对象时先改登记到新的上再拆旧的，
     * 拆的时候还没改过去的，由 [releaseAgentsRegisteredOn] 连 agent 一起收掉
     */
    private fun registerAgentSinks(): String? {
        if (agents.isEmpty()) return null
        val agentLib = MaaAgentClientLoader.library
            ?: return "libMaaAgentClient.so 加载失败，无法登记 agent 事件"
        val res = resource
        val ctrl = controller
        val tsk = synchronized(lifecycleLock) { tasker }
        if (res == null || ctrl == null || tsk == null) {
            return "登记 agent 事件时 resource / controller / tasker 未就绪"
        }
        val targets = SinkTargets(res, ctrl, tsk)
        agents.forEachIndexed { index, agent ->
            if (agent.sinks == targets) return@forEachIndexed
            val registered = agentLib.MaaAgentClientRegisterResourceSink(agent.client, res).toInt() != 0 &&
                agentLib.MaaAgentClientRegisterControllerSink(agent.client, ctrl).toInt() != 0 &&
                agentLib.MaaAgentClientRegisterTaskerSink(agent.client, tsk).toInt() != 0
            if (!registered) {
                // 可能只登记了一半，agent.sinks 已经对不上，整批收掉下一轮重拉
                releaseAgents()
                return "agent[$index] 登记事件失败"
            }
            agent.sinks = targets
            Ln.i("MaaRunner: agent[$index] sinks registered, resource=$res controller=$ctrl tasker=$tsk")
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
            if (agents.isNotEmpty()) releaseTasker(lib)
            releaseAgents()
            return null
        }
        val agentLib = MaaAgentClientLoader.library
            ?: return "libMaaAgentClient.so 加载失败，无法拉起 agent"

        val reusable = loadedAgents == payload.agents &&
            agents.size == payload.agents.size &&
            agents.all { it.session.isAlive() && agentLib.MaaAgentClientAlive(it.client).toInt() != 0 }
        if (reusable) {
            notifyAgentsConnected()
            return null
        }

        // agent 注册的 custom 节点挂在 resource 上，绑定关系要重来；上一轮的任务可能还在调 agent，先等它退
        releaseTasker(lib)
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
            // 上面的超时只给连接用。留着它会卡住每次 custom 调用：识别算过 30s 就被判超时，
            // 而 agent 算完仍会回包，这个迟到的回包会被下一次同类请求当成自己的（框架不核对 req_id），
            // 之后请求与回包整体错位，agent 拿着已销毁的 context 反调时特权进程直接崩
            agentLib.MaaAgentClientSetTimeout(client, AGENT_REQUEST_TIMEOUT_MILLIS)
            Ln.i("MaaRunner: agent[$index] connected, identifier=$identifier")
            notify { onAgentConnected(index, payload.agents.size, session.executable) }
            started += ActiveAgent(client, session)
        }

        agents = started
        loadedAgents = payload.agents
        return null
    }

    /** 复用还活着的 child 时也回投：新一轮会话日志不能因为没重新 connect 就缺这行 */
    private fun notifyAgentsConnected() {
        val alive = agents
        alive.forEachIndexed { index, active ->
            Ln.i("MaaRunner: agent[$index] reused, exec=${active.session.executable}")
            notify { onAgentConnected(index, alive.size, active.session.executable) }
        }
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
        agent.session.expectExit()
        // Disconnect 要等 child 回 ShutDown。child 已经没了就不发：ZMQ 未必察觉对端断开，会按请求超时等满，
        // 这期间下一轮起不来、destroy 也退不掉。还活着却不回的，超时后交给 close 去杀
        if (agent.session.isAlive()) {
            runCatching {
                agentLib?.MaaAgentClientSetTimeout(agent.client, AGENT_SHUTDOWN_TIMEOUT_MILLIS)
                agentLib?.MaaAgentClientDisconnect(agent.client)
            }
        }
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


    private fun takeTasker(): Pointer? = synchronized(lifecycleLock) {
        val current = tasker
        tasker = null
        current
    }

    private fun releaseTasker(lib: MaaFrameworkLibrary) {
        takeTasker()?.let { destroyTasker(lib, it) }
    }

    /**
     * 销毁前必须等任务线程真的退出：框架析构 Tasker 时先拆缓存再 join 线程，还在跑的任务会踩到已析构的锁
     * 不能靠 MaaTaskerWait：PostStop 一发，在跑的任务就被标成已结束，Wait 立刻返回
     *
     * 登记在它上面的 agent 排在等完之后、销毁之前收：退出前的任务可能还在调 agent
     */
    private fun destroyTasker(lib: MaaFrameworkLibrary, handle: Pointer) {
        lib.MaaTaskerPostStop(handle)
        while (lib.MaaTaskerRunning(handle).toInt() != 0) Thread.sleep(TASKER_STOP_POLL_MILLIS)
        releaseAgentsRegisteredOn { it.tasker == handle }
        lib.MaaTaskerDestroy(handle)
    }

    /** Tasker 手里是 controller 的裸指针，停止时还要用，必须先于它拆 */
    private fun releaseController(lib: MaaFrameworkLibrary) {
        releaseTasker(lib)
        controller?.let { destroyController(lib, it) }
        controller = null
        boundDisplayId = null
    }

    private fun destroyController(lib: MaaFrameworkLibrary, handle: Pointer) {
        releaseAgentsRegisteredOn { it.controller == handle }
        lib.MaaControllerDestroy(handle)
    }

    /** 拆对象前还有 agent 登记在它上面，又没法改登记到别处时，只能连 agent 整批收掉，下一轮重拉 */
    private fun releaseAgentsRegisteredOn(match: (SinkTargets) -> Boolean) {
        if (agents.none { agent -> agent.sinks?.let(match) == true }) return
        Ln.i("MaaRunner: releasing agents registered on a handle about to be destroyed")
        releaseAgents()
    }

    /**
     * agent client 绑在 resource 上，销毁 resource 前必须先把 client 与 child 收掉；
     * Tasker 同样握着 resource 的裸指针，排在最前
     */
    private fun releaseResource(lib: MaaFrameworkLibrary) {
        releaseTasker(lib)
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
         * 单次 custom 调用里 agent 一声不吭的上限。框架默认不限时，但 agent 中途被杀时 poll 会永远等下去，
         * 停止任务也打断不了；10 分钟远超任何正常识别，寻路这类长动作一路都有控制器往返，不会被它截断
         */
        const val AGENT_REQUEST_TIMEOUT_MILLIS = 10 * 60_000L

        /** 断开握手的上限；Tasker 已先拆掉，child 只是在等请求，正常立刻就回 */
        const val AGENT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

        const val TASKER_STOP_POLL_MILLIS = 20L

        /** destroy 等在跑的那一轮停下并拆完 native 的上限；正常停止只要几秒，超了多半是 agent 卡死 */
        const val DESTROY_TIMEOUT_MILLIS = 15_000L

        /**
         * 让系统分配回环端口；identifier 随即变成实际端口，原样传给 child
         *
         * 代价是同机任何带 INTERNET 权限的应用都能连上这个端口冒充 agent；
         * 但 unix socket 那条在 shell 域下建不出 sock_file，没有别的选择
         */
        const val AUTO_PORT: Short = 0
    }
}
