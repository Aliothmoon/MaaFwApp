package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.MaaDispatchers
import android.content.Context
import android.os.IBinder
import android.os.Process
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 单路特权服务连接
 *
 * 一个实例 = 一路：独占一个 binder、一份连接态、一份 linkToDeath 监听，以及
 * 一组按 [slot] 命名的连接器。多路并存时，每路拉起的是**独立进程**
 * （`pkg:shizuku_service_2`、`pkg:shizuku_service_3` …），因此各路进程内的
 * native 采集状态、虚拟屏、MaaFramework 句柄互不可见，天然隔离。
 *
 * 连接逻辑与旧的单例实现一致：授权刷新 → 绑定 → 等 binder 回投 → 心跳，
 * 差别只在所有可变状态都收进了实例内。
 *
 * [slot] = [ShizukuProcessServiceConnector.PRIMARY_SLOT] 时沿用历史进程名与日志名，
 * 保证单路行为与旧版一致。
 */
class SlotServiceConnection(val slot: Int) : PrivilegedServicePort {

    sealed class ServiceState {
        data object Disconnected : ServiceState()
        data object Connecting : ServiceState()
        data object Died : ServiceState()
        data class Connected(val service: RemoteService) : ServiceState()
        data class Error(val exception: Throwable) : ServiceState()
    }

    // 纯兜底：连接器自带超时先行归因，此处按其最坏时长再放余量
    private val connectTimeoutGraceMs = 2_000L

    // 调用方默认等待须晚于兜底：先于连接器超时就只剩裸超时，launcher 日志尾部等根因全被截胡
    private val callerWaitMarginMs = 1_000L

    // 状态迁移（boundBackend / currentBinder / _state）统一在此锁内完成
    private val lock = Any()

    private val currentBinder = AtomicReference<IBinder>()
    private var currentDeathRecipient: BindingDeathRecipient? = null // guarded by lock
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Disconnected)

    private val timeoutScope = CoroutineScope(MaaDispatchers.Default + SupervisorJob())
    private val connectAttempt = AtomicInteger(0)

    // 用基类类型而非接口：initialize(context) 是基类方法，接口上没有
    private val connectors: Map<RemoteBackend, ProcessServiceConnectorBackend> = mapOf(
        RemoteBackend.SHIZUKU to ShizukuProcessServiceConnector(slot),
        RemoteBackend.ROOT to RootRemoteServiceConnector(slot)
    )

    @Volatile
    private var boundBackend: RemoteBackend? = null

    private var appContext: Context? = null

    /**
     * 对外只给不带 binder 的投影：[ServiceState.Connected] 揣着 [RemoteService]，
     * 漏出这一层就等于把 IPC 句柄发给了 UI
     */
    override val serviceState: StateFlow<PrivilegedServiceState> = _state
        .map { it.toPrivilegedServiceState() }
        .stateIn(timeoutScope, SharingStarted.Eagerly, PrivilegedServiceState.Disconnected)

    /** 本类只服务一个 slot，它自己就是「当前路」，没有切换概念 */
    override val activeSlot: StateFlow<Int> = MutableStateFlow(slot)

    override val currentBackend: RemoteBackend? get() = boundBackend

    /** 连接器需要 applicationContext；每路各自初始化自己的连接器 */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        connectors.values.forEach { it.initialize(context.applicationContext) }
    }

    // 携带绑定时的 binder，迟到的死亡通知靠身份比对丢弃
    // inner：原来的 object 单例里嵌套类可以直接调外部方法，改成普通类后必须持外部实例引用
    private inner class BindingDeathRecipient(val binder: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() = onBinderDied(this)
    }

    private val connectorCallbacks = object : RemoteServiceConnectorBackend.Callbacks {
        override fun onConnected(backend: RemoteBackend, binder: IBinder) {
            val service: RemoteService
            synchronized(lock) {
                if (boundBackend != backend) {
                    ServiceBootLogger.event(
                        "CB_ON_CONNECTED_STALE",
                        "slot=$slot backend=$backend bound=$boundBackend"
                    )
                    Timber.w("Ignoring stale %s connection (slot=%d)", backend, slot)
                    return
                }
                ServiceBootLogger.event("CB_ON_CONNECTED", "slot=$slot backend=$backend")
                clearCurrentBinderLocked()
                val recipient = BindingDeathRecipient(binder)
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (e: Exception) {
                    // binder 送达时已死亡
                    ServiceBootLogger.event(
                        "CB_ON_CONNECTED_DEAD",
                        "slot=$slot backend=$backend ${e.message}"
                    )
                    Timber.e(e, "RemoteService binder dead on arrival: %s (slot=%d)", backend, slot)
                    boundBackend = null
                    _state.value = ServiceState.Error(e)
                    return
                }
                currentBinder.set(binder)
                currentDeathRecipient = recipient
                service = RemoteService.Stub.asInterface(binder)
                _state.value = ServiceState.Connected(service)
                ServiceBootLogger.event("BINDER_CONNECTED", "slot=$slot backend=$backend linkToDeath ok")
            }
            runCatching { service.heartbeat(Process.myPid()) }
                .onFailure { Timber.w(it, "heartbeat failed (slot=%d)", slot) }
        }

        override fun onDisconnected(backend: RemoteBackend) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event("CB_ON_DISCONNECTED", "slot=$slot backend=$backend")
                Timber.i("RemoteService disconnected: %s (slot=%d)", backend, slot)
                // 被动断开由服务进程死亡触发，Connected 态下收敛为 Died 保证终态确定
                val wasConnected = _state.value is ServiceState.Connected
                clearCurrentBinderLocked()
                boundBackend = null
                if (wasConnected) {
                    ServiceBootLogger.event("STATE_DIED", "slot=$slot")
                    _state.value = ServiceState.Died
                } else {
                    ServiceBootLogger.event("STATE_DISCONNECTED", "slot=$slot")
                    _state.value = ServiceState.Disconnected
                }
            }
        }

        override fun onError(backend: RemoteBackend, throwable: Throwable) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event(
                    "CB_ON_ERROR",
                    "slot=$slot backend=$backend ${throwable.javaClass.simpleName}: ${throwable.message}"
                )
                Timber.e(throwable, "RemoteService connection failed: %s (slot=%d)", backend, slot)
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(throwable)
            }
        }
    }

    private fun onBinderDied(recipient: BindingDeathRecipient) {
        synchronized(lock) {
            if (currentBinder.get() !== recipient.binder) {
                ServiceBootLogger.event("BINDER_DIED_STALE", "slot=$slot")
                return
            }
            ServiceBootLogger.event("BINDER_DIED", "slot=$slot")
            Timber.w("RemoteService binder died (slot=%d)", slot)
            clearCurrentBinderLocked()
            boundBackend = null
            ServiceBootLogger.event("STATE_DIED", "slot=$slot")
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
                Timber.w(it, "unlinkToDeath failed (slot=%d)", slot)
            }
        }
    }

    override fun bind() {
        val backend = RemoteAccessCoordinator.refresh().configuredBackend
        if (!RemoteAccessCoordinator.isGranted(backend)) {
            val exception = IllegalStateException("${backend.display} permission not granted")
            ServiceBootLogger.event("BIND_DENIED", "slot=$slot backend=$backend not granted")
            Timber.w(exception)
            synchronized(lock) {
                boundBackend = null
                _state.value = ServiceState.Error(exception)
            }
            return
        }

        val attempt: Int
        synchronized(lock) {
            if (_state.value is ServiceState.Connecting && boundBackend == backend) {
                ServiceBootLogger.event("BIND_SKIP", "slot=$slot already connecting backend=$backend")
                return
            }

            if (boundBackend != null) {
                Timber.i("Unbinding old service before binding new one (slot=%d)", slot)
                unbindLocked()
            }

            boundBackend = backend
            attempt = connectAttempt.incrementAndGet()
            ServiceBootLogger.event("BIND", "slot=$slot backend=$backend attempt=$attempt")
            _state.value = ServiceState.Connecting
            ServiceBootLogger.event("CONNECTING", "slot=$slot backend=$backend attempt=$attempt")
            connectors.getValue(backend).connect(connectorCallbacks)
        }
        startConnectTimeout(attempt, backend)
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
                    "slot=$slot still CONNECTING after ${timeoutMs}ms (backend=$backend attempt=$attempt) — service process likely failed to start or did not return binder, see service_boot_debug.log"
                )
                runCatching {
                    connectors.getValue(backend).disconnect(currentBinder.get())
                }.onFailure {
                    Timber.w(it, "disconnect after connect timeout failed (slot=%d)", slot)
                }
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(
                    TimeoutException("connect timeout after ${timeoutMs}ms (slot=$slot backend=$backend)")
                )
            }
        }
    }

    private fun fallbackTimeoutMs(backend: RemoteBackend): Long =
        connectors.getValue(backend).worstCaseConnectMs + connectTimeoutGraceMs

    /** [timeoutMs] 为 null 时按当前后端推算默认等待 */
    private fun defaultWaitMs(backend: RemoteBackend): Long =
        fallbackTimeoutMs(backend) + callerWaitMarginMs

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
                ServiceBootLogger.event("STATE_DISCONNECTED", "slot=$slot")
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
            ServiceBootLogger.event(
                "GET_INSTANCE_TIMEOUT",
                "slot=$slot after ${waitMs}ms state=${_state.value}"
            )
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

    override suspend fun <R> useService(action: suspend (RemoteService) -> R): R {
        var accessState = RemoteAccessCoordinator.refresh()
        var backend = accessState.configuredBackend
        if (!accessState.isGranted(backend)) {
            val granted = RemoteAccessCoordinator.request(backend)
            accessState = RemoteAccessCoordinator.refresh()
            backend = accessState.configuredBackend
            if (!granted || !accessState.isGranted(backend)) {
                throw IllegalStateException("${backend.display} permission not granted")
            }
        }

        if (boundBackend != null && boundBackend != backend) {
            Timber.i("Rebinding remote service from %s to %s (slot=%d)", boundBackend, backend, slot)
            unbind()
        }

        val service = getInstance()
        return action(service)
    }

    /**
     * 释放这一路：解绑并把连接器残留进程清掉。
     * 进程侧的虚拟屏、游戏与采集线程随进程退出一起消失，不需要在这里逐个收
     */
    fun release() {
        runCatching { unbind() }
            .onFailure { Timber.w(it, "release unbind failed (slot=%d)", slot) }
        runCatching { timeoutScope.cancel() }
    }
}

private fun SlotServiceConnection.ServiceState.toPrivilegedServiceState() = when (this) {
    is SlotServiceConnection.ServiceState.Connected -> PrivilegedServiceState.Connected
    SlotServiceConnection.ServiceState.Connecting -> PrivilegedServiceState.Connecting
    SlotServiceConnection.ServiceState.Died -> PrivilegedServiceState.Died
    SlotServiceConnection.ServiceState.Disconnected -> PrivilegedServiceState.Disconnected
    is SlotServiceConnection.ServiceState.Error -> PrivilegedServiceState.Error
}
