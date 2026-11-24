package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.MaaDispatchers
import android.content.Context
import android.os.IBinder
import android.os.Process
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.constant.ShellDirs
import com.aliothmoon.maafw.root.BootstrapRegistry
import com.aliothmoon.maafw.root.ProcessExit
import com.aliothmoon.maafw.root.RootServiceBootstrapRegistry
import com.aliothmoon.maafw.root.RootServiceStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException
import com.aliothmoon.maafw.util.userIdOf

/**
 * 自研 starter 进程路径的公共基座：launcher 拉起 app_process，
 * binder 回投经 MaaFw 自己的 bootstrap provider 按 token 认领，
 * 生命周期由 appLifecycleBinder linkToDeath + 心跳看门狗自管
 *
 * 拉起方式由 [spawner] 决定（su 后台化 / Shizuku 前台 exec），服务规格由子类给出
 */
abstract class ProcessServiceConnectorBackend(
    private val spawner: ProcessSpawner,
    private val registry: BootstrapRegistry = RootServiceBootstrapRegistry,
) : RemoteServiceConnectorBackend {

    protected open val spawnTimeoutMs: Long = 15_000L

    /** 同名残留的退出宽限：比服务退出期限长，正在收尾的旧进程不误杀 */
    protected open val residualGraceMs: Long = ProcessExit.DEADLINE_MS + 1_000L

    // spawn 同步调用、残留等待与超时后的 kill / dump 不在 withTimeout 内，另计余量
    override val worstCaseConnectMs: Long
        get() = spawnTimeoutMs + residualGraceMs + SPAWN_OVERHEAD_MS

    protected abstract val eventPrefix: String

    /** 同时是残留清理的匹配键 */
    protected abstract val processNameSuffix: String

    protected abstract val serviceClass: Class<*>

    /** 各连接器独立，launcher O_TRUNC 打开，共用会互相覆盖 */
    protected abstract val logFileName: String

    // 单并发：spawn 与 killResidual 都是阻塞调用，独占队列位，先入队者先做完
    private val scope = CoroutineScope(MaaDispatchers.IO.limitedParallelism(1) + SupervisorJob())

    @Volatile
    private var activeLaunch: ActiveLaunch? = null

    private lateinit var appContext: Context

    val isConnecting: Boolean get() = activeLaunch?.job?.isActive == true

    private val processName: String
        get() = elevatedProcessName(appContext.packageName, processNameSuffix, appContext.applicationInfo.uid)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        check(::appContext.isInitialized) { "${javaClass.simpleName} is not initialized" }
        // 取代进行中的旧拉起：旧 token 即刻注销，旧进程回投会被 provider 拒绝而自退
        dropActiveLaunch()
        val token = UUID.randomUUID().toString()
        val deferred = registry.register(token)

        val job = scope.launch {
            val logFile = debugLogFile()
            var spawned = false
            // 整段包住：check(launcherFile) 等异常逃逸会让协程未捕获致崩溃
            val result = runCatching {
                reapResidual()
                // 先清旧日志，确保 dump 到的一定是本次启动的产物
                logFile.delete()
                val command = buildStartCommand(token, logFile)
                ServiceBootLogger.event("${eventPrefix}_SPAWN_CALL", "token=$token")
                val handle = spawner.spawn(command)
                spawned = true
                awaitBinder(deferred, handle)
            }
            if (activeLaunch?.token != token) {
                registry.unregister(token)
                // 被取代前已回投的进程没人认领，不收就成了孤儿
                arrivedBinder(result, deferred)?.let { binder ->
                    runCatching { destroyRemote(binder) }
                        .onFailure { Timber.w(it, "destroy superseded %s binder failed", backend) }
                }
                return@launch
            }
            result.onSuccess { binder ->
                onBinderArrived(token, binder, callbacks)
            }.onFailure { throwable ->
                activeLaunch = null
                registry.unregister(token)
                // 进程路径没有 server 端 record 代杀，卡在半路的服务进程只能自己清
                if (spawned) spawner.killResidual(processName)
                reportFailure(token, throwable, logFile, callbacks)
            }
        }
        activeLaunch = ActiveLaunch(token, job)
    }

    override fun disconnect(currentBinder: IBinder?) {
        dropActiveLaunch()
        if (currentBinder != null) {
            runCatching { destroyRemote(currentBinder) }
                .onFailure { Timber.w(it, "destroy %s remote service failed", backend) }
        } else {
            // 没连上就断开：进程可能卡在半路，排进单并发队列先清后起
            scope.launch { spawner.killResidual(processName) }
        }
    }

    protected open fun destroyRemote(binder: IBinder) {
        RemoteService.Stub.asInterface(binder)?.destroy()
    }

    protected open fun buildStartCommand(token: String, logFile: File): String {
        val launcher = File(appContext.applicationInfo.nativeLibraryDir, "liblauncher.so")
        check(launcher.exists()) { "launcher not found: ${launcher.absolutePath}" }
        val appUid = Process.myUid()
        val invocation = buildString {
            append(shellQuote(launcher.absolutePath))
            append(" --apk=").append(shellQuote(appContext.applicationInfo.sourceDir))
            append(" --process-name=").append(shellQuote(processName))
            append(" --starter-class=").append(shellQuote(RootServiceStarter::class.java.name))
            append(" --token=").append(shellQuote(token))
            append(" --package=").append(shellQuote(appContext.packageName))
            append(" --class=").append(shellQuote(serviceClass.name))
            append(" --uid=").append(appUid)
            append(" --log-file=").append(shellQuote(logFile.absolutePath))
            if (BuildConfig.DEBUG) {
                append(" --debug-name=").append(shellQuote(processName))
            }
        }
        // 两个后端都降到 shell；以前留 root 时在 /data/local/tmp 与外部私有目录写下的 uid 0 条目，每次拉起前修回
        return "${shellOwnershipRepairCommand(ShellDirs.AGENT_ROOT.absolutePath)}; " +
            "${externalFilesRepairCommand(AppPaths.ROOT.absolutePath, appContext.packageName, appUid)}; " +
            spawner.wrapCommand(launcher.absolutePath, invocation)
    }

    /** 取消会盖掉 await 的结果，回投是否已到还得看 deferred */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun arrivedBinder(result: Result<IBinder>, deferred: Deferred<IBinder>): IBinder? =
        result.getOrNull() ?: deferred.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()

    /**
     * 拉起前清同名残留：正常退出的进程宽限内会自己走，超期还在就是孤儿，硬杀并留痕
     * 所在队列单并发，不会误杀本连接器随后拉起的新进程
     */
    private suspend fun reapResidual() {
        var residual = spawner.findProcesses(processName)
        if (residual.isEmpty()) return
        val found = residual.joinToString { "pid=${it.pid} etime=${it.elapsed}" }
        ServiceBootLogger.event("${eventPrefix}_RESIDUAL_FOUND", found)
        Timber.w("Residual %s found before spawn: %s", processName, found)
        repeat((residualGraceMs / RESIDUAL_POLL_MS).toInt()) {
            delay(RESIDUAL_POLL_MS)
            residual = spawner.findProcesses(processName)
            if (residual.isEmpty()) {
                ServiceBootLogger.event("${eventPrefix}_RESIDUAL_EXITED", found)
                return
            }
        }
        val pids = residual.map { it.pid }
        ServiceBootLogger.event("${eventPrefix}_RESIDUAL_KILLED", "pids=$pids")
        Timber.e("Residual %s still alive after %dms, force killing %s", processName, residualGraceMs, pids)
        spawner.forceKill(pids)
    }

    protected open fun debugLogFile(): File {
        val dir = AppPaths.DEBUG_DIR
        dir.mkdirs()
        return File(dir, logFileName)
    }

    private fun dropActiveLaunch() {
        val previous = activeLaunch ?: return
        activeLaunch = null
        previous.job.cancel()
        registry.unregister(previous.token)
    }

    /** binder 回投与进程存活竞速：进程先退出立即失败并携带退出码，不等满超时 */
    private suspend fun awaitBinder(deferred: Deferred<IBinder>, handle: SpawnHandle?): IBinder =
        withTimeout(spawnTimeoutMs) {
            if (handle == null) return@withTimeout deferred.await()
            coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        if (!handle.isAlive()) throw ProcessExitedException(handle.exitCode())
                        delay(ALIVE_POLL_INTERVAL_MS)
                    }
                }
                try {
                    deferred.await()
                } finally {
                    watcher.cancel()
                }
            }
        }

    private fun onBinderArrived(
        token: String,
        binder: IBinder,
        callbacks: RemoteServiceConnectorBackend.Callbacks,
    ) {
        // token 认领：旧进程死讯不能误伤新连接
        runCatching {
            binder.linkToDeath({
                if (activeLaunch?.token != token) {
                    Timber.i("Stale %s process binder death ignored (token=%s)", processName, token)
                    return@linkToDeath
                }
                Timber.e("%s process died unexpectedly.", processName)
                callbacks.onDisconnected(backend)
            }, 0)
        }.onFailure {
            Timber.w(it, "linkToDeath failed for %s process binder", processName)
        }

        Timber.i("%s connected by %s bootstrap", processName, backend)
        ServiceBootLogger.event("${eventPrefix}_PROCESS_CONNECTED", "token=$token")
        callbacks.onConnected(backend, binder)
    }

    private fun reportFailure(
        token: String,
        throwable: Throwable,
        logFile: File,
        callbacks: RemoteServiceConnectorBackend.Callbacks,
    ) {
        val error = describeFailure(throwable, dumpSpawnDebugLog(logFile))
        ServiceBootLogger.event(
            "${eventPrefix}_PROCESS_FAIL",
            "token=$token ${error.javaClass.simpleName}: ${error.message}"
        )
        Timber.e(error, "%s start failed: token=%s", processName, token)
        callbacks.onError(backend, error)
    }

    private fun describeFailure(throwable: Throwable, logTail: String?): Throwable {
        val suffix = if (logTail.isNullOrBlank()) "" else "; launcher log tail: $logTail"
        return when {
            throwable is TimeoutCancellationException ->
                TimeoutException("$processName binder not attached within ${spawnTimeoutMs}ms$suffix")

            suffix.isEmpty() -> throwable
            else -> IllegalStateException("${throwable.message}$suffix", throwable)
        }
    }

    private fun dumpSpawnDebugLog(log: File): String? {
        if (!log.exists()) {
            Timber.e("%s launch debug log not found: %s", processName, log.absolutePath)
            return null
        }
        val lines =
            runCatching { log.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            Timber.e(
                "%s launch debug log is empty (launcher may have crashed before opening it)",
                processName
            )
            return null
        }
        Timber.e(
            "%s launch debug log (%s):\n%s",
            processName,
            log.absolutePath,
            lines.joinToString("\n")
        )
        return lines.takeLast(LOG_TAIL_LINES).joinToString(" | ")
    }

    private data class ActiveLaunch(
        val token: String,
        val job: Job,
    )

    private companion object {
        const val SPAWN_OVERHEAD_MS = 3_000L
        const val ALIVE_POLL_INTERVAL_MS = 400L
        const val RESIDUAL_POLL_MS = 300L
        const val LOG_TAIL_LINES = 5
    }
}

/**
 * 分身等非主用户的实例共用同一个 Shizuku，同名进程会被对方当残留硬杀
 * 主用户保持原名，升级前的残留照清
 */
internal fun elevatedProcessName(packageName: String, suffix: String, uid: Int): String {
    val userId = userIdOf(uid)
    return if (userId == 0) "$packageName:$suffix" else "$packageName:${suffix}_u$userId"
}

