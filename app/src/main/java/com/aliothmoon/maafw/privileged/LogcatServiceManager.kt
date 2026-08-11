package com.aliothmoon.maafw.privileged
import com.aliothmoon.maafw.MaaDispatchers

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.ILogcatService
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.remote.LogcatCaptureServiceImpl
import com.aliothmoon.maafw.root.RootServiceBootstrapRegistry
import com.aliothmoon.maafw.root.RootServiceStarter
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 logcat 抓取服务拉进独立特权进程（:logcat / :root_logcat），与主 RemoteService 同后端不同进程
 *
 * 复用现有 launcher.c + RootServiceStarter + RootServiceBootstrapRegistry：
 * Shizuku 走 bindUserService（processNameSuffix logcat），root 走 launcher（--class 指向 LogcatCaptureServiceImpl）；
 * 后端由调用方显式传入，不自行读 RemoteAccessCoordinator，便于测试与解耦
 */
object LogcatServiceManager {

    private const val ROOT_BIND_TIMEOUT_MS = 15_000L

    private val _service = MutableStateFlow<ILogcatService?>(null)

    // --- Shizuku ---
    private val serviceTag = UUID.randomUUID().toString()
    private val serviceVersion = AtomicInteger(100)
    private var currentArgs: Shizuku.UserServiceArgs? = null
    private var shizukuConnection: ServiceConnection? = null

    // --- Root ---
    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context
    private val scope = CoroutineScope(MaaDispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private var rootActiveLaunch: RootActiveLaunch? = null

    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            appContext = context.applicationContext
        }
    }

    fun bind(backend: RemoteBackend) {
        if (_service.value != null) return
        when (backend) {
            RemoteBackend.SHIZUKU -> bindViaShizuku()
            RemoteBackend.ROOT -> bindViaRoot()
        }
    }

    fun unbind() {
        currentArgs?.let { args ->
            currentArgs = null
            shizukuConnection?.let { conn ->
                runCatching { Shizuku.unbindUserService(args, conn, true) }
                    .onFailure { Timber.w(it, "unbind logcat shizuku service failed") }
            }
            shizukuConnection = null
        }

        rootActiveLaunch?.let { active ->
            rootActiveLaunch = null
            active.job.cancel()
            RootServiceBootstrapRegistry.unregister(active.token)
            _service.value?.let { svc -> runCatching { svc.destroy() } }
        }

        _service.value = null
    }

    suspend fun startCapture(appPid: Int, servicePid: Int, userDir: String) {
        withTimeout(10_000) { _service.first { it != null } }
            ?.startCapture(appPid, servicePid, userDir)
    }

    // --- Shizuku ---

    private fun bindViaShizuku() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, LogcatCaptureServiceImpl::class.java.name)
        ).apply {
            processNameSuffix("logcat")
            daemon(false)
            tag(serviceTag)
            version(serviceVersion.incrementAndGet())
            debuggable(BuildConfig.DEBUG)
        }
        currentArgs = args

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Timber.i("LogcatService connected via Shizuku: %s", name)
                _service.value = ILogcatService.Stub.asInterface(binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Timber.i("LogcatService disconnected via Shizuku: %s", name)
                _service.value = null
            }
        }
        shizukuConnection = conn

        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure { Timber.e(it, "bindLogcatService via Shizuku failed") }
    }

    // --- Root ---

    private fun bindViaRoot() {
        check(initialized.get()) { "LogcatServiceManager is not initialized for root mode" }

        val token = UUID.randomUUID().toString()
        val deferred = RootServiceBootstrapRegistry.register(token)
        val job = scope.launch {
            val startResult = withContext(MaaDispatchers.IO) { startRootService(token) }
            val active = rootActiveLaunch
            if (active?.token != token) {
                RootServiceBootstrapRegistry.unregister(token)
                return@launch
            }

            val startError = startResult.exceptionOrNull()
            if (startError != null) {
                rootActiveLaunch = null
                RootServiceBootstrapRegistry.unregister(token)
                Timber.e(startError, "Root logcat service start failed")
                return@launch
            }

            runCatching { withTimeout(ROOT_BIND_TIMEOUT_MS) { deferred.await() } }
                .onSuccess { binder ->
                    if (rootActiveLaunch?.token != token) {
                        RootServiceBootstrapRegistry.unregister(token)
                        return@onSuccess
                    }
                    Timber.i("LogcatService connected via root bootstrap")
                    _service.value = ILogcatService.Stub.asInterface(binder)
                }
                .onFailure { throwable ->
                    RootServiceBootstrapRegistry.unregister(token)
                    if (rootActiveLaunch?.token == token) {
                        rootActiveLaunch = null
                        Timber.e(throwable, "Root logcat bind timeout")
                    }
                }
        }
        rootActiveLaunch = RootActiveLaunch(token, job)
    }

    private fun startRootService(token: String): Result<Unit> = runCatching {
        val result = Shell.cmd(buildRootCommand(token)).exec()
        if (result.code != 0) {
            error(result.err.joinToString("\n").ifBlank { "exit code=${result.code}" })
        }
    }

    private fun buildRootCommand(token: String): String {
        val processName = "${appContext.packageName}:root_logcat"
        val launcher = File(appContext.applicationInfo.nativeLibraryDir, "liblauncher.so")
        check(launcher.exists()) { "root launcher not found: ${launcher.absolutePath}" }
        val uid = Process.myUid()
        return buildString {
            append(shellQuote(launcher.absolutePath))
            append(" --apk=").append(shellQuote(appContext.applicationInfo.sourceDir))
            append(" --process-name=").append(shellQuote(processName))
            append(" --starter-class=").append(shellQuote(RootServiceStarter::class.java.name))
            append(" --token=").append(shellQuote(token))
            append(" --package=").append(shellQuote(appContext.packageName))
            append(" --class=").append(shellQuote(LogcatCaptureServiceImpl::class.java.name))
            append(" --uid=").append(uid)
            // logcat 无需输入注入，shell 身份自带 log 组权限；不加 --keep-root（对齐 MaaMeow）
            append(" --log-file=").append(shellQuote(debugLogFile().absolutePath))
            if (BuildConfig.DEBUG) append(" --debug-name=").append(shellQuote(processName))
            append(" >/dev/null 2>&1 &")
        }
    }

    // 与主服务日志分开：launcher 以 O_TRUNC 打开，共用会互相覆盖
    private fun debugLogFile(): File {
        val dir = AppPaths.DEBUG_DIR
        dir.mkdirs()
        return File(dir, "root_logcat_launch_debug.log")
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class RootActiveLaunch(val token: String, val job: Job)
}
