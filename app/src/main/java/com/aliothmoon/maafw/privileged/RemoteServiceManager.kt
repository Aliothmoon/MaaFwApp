package com.aliothmoon.maafw.privileged
import com.aliothmoon.maafw.MaaDispatchers

import android.content.Context
import android.os.IBinder
import android.os.Process
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object RemoteServiceManager : PrivilegedServicePort {

    sealed class ServiceState {
        data object Disconnected : ServiceState()
        data object Connecting : ServiceState()
        data object Died : ServiceState()
        data class Connected(val service: RemoteService) : ServiceState()
        data class Error(val exception: Throwable) : ServiceState()
    }

    // 纯兜底：连接器自带超时先行归因，此处按其最坏时长再放余量
    private const val CONNECT_TIMEOUT_GRACE_MS = 2_000L

    // 调用方默认等待须晚于兜底：先于连接器超时就只剩裸超时，launcher 日志尾部等根因全被截胡
    private const val CALLER_WAIT_MARGIN_MS = 1_000L

    // 状态迁移（boundBackend / currentBinder / _state）统一在此锁内完成
    private val lock = Any()

    private val currentBinder = AtomicReference<IBinder>()
    private var currentDeathRecipient: BindingDeathRecipient? = null // guarded by lock
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Disconnected)

    private val timeoutScope = CoroutineScope(MaaDispatchers.Default + SupervisorJob())
    private val connectAttempt = AtomicInteger(0)

    private val connectors: Map<RemoteBackend, RemoteServiceConnectorBackend> = mapOf(
        RemoteBackend.SHIZUKU to ShizukuProcessServiceConnector,
        RemoteBackend.ROOT to RootRemoteServiceConnector
    )

    @Volatile
    private var boundBackend: RemoteBackend? = null

    // 拉起进程时的身份（uid），guarded by lock
    private var boundIdentity: Int? = null

    /**
     * 对外只给不带 binder 的投影：[ServiceState.Connected] 揣着 [RemoteService]，
     * 漏出这一层就等于把 IPC 句柄发给了 UI
     */
    override val serviceState: StateFlow<PrivilegedServiceState> = _state
        .map { it.toPrivilegedServiceState() }
        .stateIn(timeoutScope, SharingStarted.Eagerly, PrivilegedServiceState.Disconnected)

    override val currentBackend: RemoteBackend? get() = boundBackend

    // 携带绑定时的 binder，迟到的死亡通知靠身份比对丢弃
    private class BindingDeathRecipient(val binder: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() = onBinderDied(this)
    }

    private val connectorCallbacks = object : RemoteServiceConnectorBackend.Callbacks {
        override fun onConnected(backend: RemoteBackend, binder: IBinder) {
            val service: RemoteService
            synchronized(lock) {
                if (boundBackend != backend) {
                    ServiceBootLogger.event("CB_ON_CONNECTED_STALE", "backend=$backend bound=$boundBackend")
                    Timber.w("Ignoring stale %s connection", backend)
                    return
                }
                ServiceBootLogger.event("CB_ON_CONNECTED", "backend=$backend")
                clearCurrentBinderLocked()
                val recipient = BindingDeathRecipient(binder)
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (e: Exception) {
                    // binder 送达时已死亡
                    ServiceBootLogger.event("CB_ON_CONNECTED_DEAD", "backend=$backend ${e.message}")
                    Timber.e(e, "RemoteService binder dead on arrival: %s", backend)
                    boundBackend = null
                    _state.value = ServiceState.Error(e)
                    return
                }
                currentBinder.set(binder)
                currentDeathRecipient = recipient
                service = RemoteService.Stub.asInterface(binder)
                _state.value = ServiceState.Connected(service)
                ServiceBootLogger.event("BINDER_CONNECTED", "backend=$backend linkToDeath ok")
            }
            runCatching { service.heartbeat(Process.myPid()) }
                .onFailure { Timber.w(it, "heartbeat failed") }
        }

        override fun onDisconnected(backend: RemoteBackend) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event("CB_ON_DISCONNECTED", "backend=$backend")
                Timber.i("RemoteService disconnected: %s", backend)
                // 被动断开由服务进程死亡触发，Connected 态下收敛为 Died 保证终态确定
                val wasConnected = _state.value is ServiceState.Connected
                clearCurrentBinderLocked()
                boundBackend = null
                if (wasConnected) {
                    ServiceBootLogger.event("STATE_DIED")
                    _state.value = ServiceState.Died
                } else {
                    ServiceBootLogger.event("STATE_DISCONNECTED")
                    _state.value = ServiceState.Disconnected
                }
            }
        }

        override fun onError(backend: RemoteBackend, throwable: Throwable) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event("CB_ON_ERROR", "backend=$backend ${throwable.javaClass.simpleName}: ${throwable.message}")
                Timber.e(throwable, "RemoteService connection failed: %s", backend)
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(throwable)
            }
        }
    }

    fun initialize(
        context: Context,
        backendProvider: () -> RemoteBackend,
    ) {
        ServiceBootLogger.init()
        ShizukuManager.initSui(context.packageName)
        RemoteAccessCoordinator.initialize(backendProvider)
        initializeConnectors(context)
    }

    /** 连接器只要 applicationContext；PermissionManager 构造期「已授权即 bind」早于 [initialize]，须先注入 */
    fun initializeConnectors(context: Context) {
        RootRemoteServiceConnector.initialize(context)
        ShizukuProcessServiceConnector.initialize(context)
        LogcatServiceManager.initialize(context)
    }

    private fun onBinderDied(recipient: BindingDeathRecipient) {
        synchronized(lock) {
            if (currentBinder.get() !== recipient.binder) {
                ServiceBootLogger.event("BINDER_DIED_STALE")
                return
            }
            ServiceBootLogger.event("BINDER_DIED")
            Timber.w("RemoteService binder died")
            clearCurrentBinderLocked()
            boundBackend = null
            ServiceBootLogger.event("STATE_DIED")
            _state.value = ServiceState.Died
        }
    }

    private fun clearCurrentBinderLocked() {
        val binder = currentBinder.getAndSet(null)
        val recipient = currentDeathRecipient
        currentDeathRecipient = null
        if (binder != null && recipient != null) {
            runCatching {
                binder.unlinkToDeath(recipient, 0)
            }.onFailure {
                Timber.w(it, "unlinkToDeath failed")
            }
        }
    }

    override fun bind() {
        val access = RemoteAccessCoordinator.refresh()
        val backend = access.configuredBackend
        if (!access.isGranted(backend)) {
            val exception = RemoteAccessException(backend, access.failureOf(backend))
            ServiceBootLogger.event("BIND_DENIED", "backend=$backend ${exception.failure}")
            Timber.w(exception)
            synchronized(lock) {
                boundBackend = null
                _state.value = ServiceState.Error(exception)
            }
            return
        }

        val identity = spawnIdentity(backend)
        val attempt: Int
        synchronized(lock) {
            if (_state.value is ServiceState.Connecting && boundBackend == backend) {
                ServiceBootLogger.event("BIND_SKIP", "already connecting backend=$backend")
                return
            }
            // Shizuku / su 重启不影响已经拉起的进程；授权一恢复就重绑会把在跑的那一轮打断。
            // 身份变了（adb 起的 Shizuku 换成 root 起的）才重起，旧进程的权限不对
            if (_state.value is ServiceState.Connected && boundBackend == backend &&
                currentBinder.get()?.isBinderAlive == true &&
                identity != null && identity == boundIdentity
            ) {
                ServiceBootLogger.event("BIND_SKIP", "already connected backend=$backend uid=$identity")
                Timber.i("Keep connected %s service (uid=%d)", backend, identity)
                return
            }

            if (boundBackend != null) {
                Timber.i("Unbinding old service before binding new one")
                unbindLocked()
            }

            boundBackend = backend
            boundIdentity = identity
            attempt = connectAttempt.incrementAndGet()
            ServiceBootLogger.event("BIND", "backend=$backend attempt=$attempt")
            _state.value = ServiceState.Connecting
            ServiceBootLogger.event("CONNECTING", "backend=$backend attempt=$attempt")
            connectors.getValue(backend).connect(connectorCallbacks)
        }
        startConnectTimeout(attempt, backend)
    }

    private fun spawnIdentity(backend: RemoteBackend): Int? = when (backend) {
        RemoteBackend.SHIZUKU -> ShizukuManager.serverUid()
        RemoteBackend.ROOT -> 0
    }

    private fun startConnectTimeout(attempt: Int, backend: RemoteBackend) {
        val timeoutMs = fallbackTimeoutMs(backend)
        timeoutScope.launch {
            delay(timeoutMs)
            synchronized(lock) {
                if (connectAttempt.get() != attempt ||
                    _state.value !is ServiceState.Connecting ||
                    boundBackend != backend
                ) {
                    return@launch
                }
                ServiceBootLogger.event(
                    "CONNECT_TIMEOUT",
                    "still CONNECTING after ${timeoutMs}ms (backend=$backend attempt=$attempt) — service process likely failed to start or did not return binder, see service_boot_debug.log"
                )
                runCatching {
                    connectors.getValue(backend).disconnect(currentBinder.get())
                }.onFailure {
                    Timber.w(it, "disconnect after connect timeout failed")
                }
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(
                    TimeoutException("connect timeout after ${timeoutMs}ms (backend=$backend)")
                )
            }
        }
    }

    private fun fallbackTimeoutMs(backend: RemoteBackend): Long =
        connectors.getValue(backend).worstCaseConnectMs + CONNECT_TIMEOUT_GRACE_MS

    /** [timeoutMs] 为 null 时按当前后端推算默认等待 */
    private fun defaultWaitMs(backend: RemoteBackend): Long =
        fallbackTimeoutMs(backend) + CALLER_WAIT_MARGIN_MS

    private fun unbindLocked() {
        val backend = boundBackend ?: return
        connectors.getValue(backend).disconnect(currentBinder.get())
        clearCurrentBinderLocked()
        boundBackend = null
    }

    override fun unbind() {
        synchronized(lock) {
            if (_state.value == ServiceState.Disconnected && boundBackend == null) {
                return
            }
            unbindLocked()
            // 主动解绑不覆盖待消费的 Died 信号
            if (_state.value != ServiceState.Died) {
                ServiceBootLogger.event("STATE_DISCONNECTED")
                _state.value = ServiceState.Disconnected
            }
        }
    }

    suspend fun getInstance(timeoutMs: Long? = null): RemoteService {
        serviceOrNull()?.let { return it }

        bind()
        val waitMs =
            timeoutMs ?: defaultWaitMs(boundBackend ?: RemoteAccessCoordinator.configuredBackend())
        return try {
            withTimeout(waitMs) {
                _state.first { it is ServiceState.Connected || it is ServiceState.Error }
                    .let { currentState ->
                        when (currentState) {
                            is ServiceState.Connected -> currentState.service
                            is ServiceState.Error -> throw currentState.exception
                            else -> error("Unexpected state: $currentState")
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            ServiceBootLogger.event("GET_INSTANCE_TIMEOUT", "after ${waitMs}ms state=${_state.value}")
            throw e
        }
    }

    override fun serviceOrNull(): RemoteService? {
        val current = _state.value
        return if (current is ServiceState.Connected) current.service else null
    }

    /** 当前已连接的后端；未连接返回 null */
    fun connectedBackendOrNull(): RemoteBackend? =
        if (_state.value is ServiceState.Connected) boundBackend else null


    // 整体切到 IO：授权刷新与 getInstance 里的 bind 都是同步 binder 调用，调用方忘了切就是 ANR
    override suspend fun <R> useService(action: suspend (RemoteService) -> R): R = withContext(MaaDispatchers.IO) {
        ensureAccess()?.let { throw RemoteAccessException(it.backend, it.failure) }
        val service = getInstance()
        action(service)
    }

    override suspend fun connect(): ServiceConnectResult = withContext(MaaDispatchers.IO) { connectOnIo() }

    private suspend fun connectOnIo(): ServiceConnectResult {
        ensureAccess()?.let { return it }
        val backend = boundBackend ?: RemoteAccessCoordinator.configuredBackend()
        return try {
            getInstance()
            ServiceConnectResult.Connected
        } catch (e: TimeoutCancellationException) {
            // 等连接超时与调用方自己的超时是同一个异常类型，外层取消了就别吞
            currentCoroutineContext().ensureActive()
            ServiceConnectResult.Failed(backend, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServiceConnectResult.Failed(backend, e)
        }
    }

    /** 未授权先请求一次；就绪返回 null，并把绑定切到当前后端 */
    private suspend fun ensureAccess(): ServiceConnectResult.NotReady? {
        // 已连着当前后端就直接用：Shizuku / su 停了，它拉起的进程照样活着，停止与新一轮都还能走
        if (serviceOrNull() != null && boundBackend == RemoteAccessCoordinator.configuredBackend()) return null

        var accessState = RemoteAccessCoordinator.refresh()
        var backend = accessState.configuredBackend
        if (!accessState.isGranted(backend)) {
            val granted = RemoteAccessCoordinator.request(backend)
            accessState = RemoteAccessCoordinator.refresh()
            backend = accessState.configuredBackend
            if (!granted || !accessState.isGranted(backend)) {
                return ServiceConnectResult.NotReady(backend, accessState.failureOf(backend))
            }
        }

        if (boundBackend != null && boundBackend != backend) {
            Timber.i("Rebinding remote service from %s to %s", boundBackend, backend)
            unbind()
        }
        return null
    }
}

/** 只在未授权时有意义：没在跑也就谈不上授权，两者分开报 */
private fun RemoteAccessState.failureOf(backend: RemoteBackend): RemoteAccessFailure =
    if (isAvailable(backend)) RemoteAccessFailure.NOT_GRANTED else RemoteAccessFailure.UNAVAILABLE

private fun RemoteServiceManager.ServiceState.toPrivilegedServiceState() = when (this) {
    is RemoteServiceManager.ServiceState.Connected -> PrivilegedServiceState.Connected
    RemoteServiceManager.ServiceState.Connecting -> PrivilegedServiceState.Connecting
    RemoteServiceManager.ServiceState.Died -> PrivilegedServiceState.Died
    RemoteServiceManager.ServiceState.Disconnected -> PrivilegedServiceState.Disconnected
    is RemoteServiceManager.ServiceState.Error -> PrivilegedServiceState.Error
}
