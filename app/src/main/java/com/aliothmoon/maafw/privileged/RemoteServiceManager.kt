package com.aliothmoon.maafw.privileged

import android.content.Context
import android.content.SharedPreferences
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.config.ActiveSlotProvider
import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 特权服务门面（单路流程的既有入口，同时是多路的总入口）
 *
 * 历史实现是「一个进程 + 一个 binder」的单例；多路改造把连接逻辑下沉到
 * [SlotServiceConnection]，本对象退化为门面，并多了一层「当前操作哪一路」：
 *
 * - [serviceState] / [useService] / [serviceOrNull] 等 [PrivilegedServicePort] 成员
 *   一律按 [activeSlot] 分发。默认仍是 0 号路，所以既有单路代码一行都不用改；
 * - 切路用 [setActiveSlot]，它只改「UI 现在操作谁」，**不动任何一路正在跑的任务**——
 *   多开下各路本就各跑各的，切视角不该停别人的活；
 * - 建过的路号写进 SharedPreferences，下次启动照着把各路重新拉起来（编号留着，
 *   但进程是新的，必须重新 bind）；
 * - 多路场景用 [connection] 按路号取用，[allocateSlot] / [releaseSlot] 管路号。
 *
 * 每路一个独立服务进程（`pkg:shizuku_service_N`），所以在每路进程内部，
 * [VirtualDisplayManager] 之类的 object 单例天然等价于「这一路自己的实例」。
 */
object RemoteServiceManager : PrivilegedServicePort {

    private const val PREFS_NAME = "maafw_multi_instance"
    private const val KEY_SLOTS = "created_slots"

    private val connections = ConcurrentHashMap<Int, SlotServiceConnection>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    // serviceState 要跟着 activeSlot 走，flatMapLatest 需要一个长期作用域
    private val facadeScope = CoroutineScope(MaaDispatchers.Default + SupervisorJob())

    // 启动时把上次建的路重新拉起来：编号是持久的，进程是新的，bind 得重来
    private val restoreScope = CoroutineScope(MaaDispatchers.IO + SupervisorJob())

    /**
     * 持久化用的路号集合，独立于 [connections] 维护
     *
     * 不能在 connections.computeIfAbsent 里回头去读 connections——那是在同一个 map 上
     * 的重入操作，ConcurrentHashMap 的契约不允许
     */
    private val persistedSlots = ConcurrentHashMap.newKeySet<Int>()

    // ── 当前操作路 ──

    private val _activeSlot = MutableStateFlow(ShizukuProcessServiceConnector.PRIMARY_SLOT)

    /** UI 当前正在操作的路号；门面所有成员按它分发 */
    override val activeSlot: StateFlow<Int> = _activeSlot.asStateFlow()

    /**
     * 切到某一路。未建立的路拒绝（返回 false），调用方据此提示用户先去设置页新建。
     * 不做任何停机动作：别的路该跑继续跑
     */
    fun setActiveSlot(slot: Int): Boolean {
        if (!connections.containsKey(slot)) {
            Timber.w("setActiveSlot(%d) refused: slot not created", slot)
            return false
        }
        _activeSlot.value = slot
        // 配置层（每路一套配置）读不到 privileged，切路要顺手同步过去
        ActiveSlotProvider.set(slot)
        Timber.i("RemoteServiceManager: active slot -> %d", slot)
        return true
    }

    /** 门面统一按当前选中路取连接 */
    private val active: SlotServiceConnection get() = connection(_activeSlot.value)

    /** 0 号路：初始化与单路底座 */
    private val primary: SlotServiceConnection
        get() = connection(ShizukuProcessServiceConnector.PRIMARY_SLOT)

    /** 按路号取连接，不存在则创建（并补上初始化）。多路的路号在这里被记进持久化 */
    fun connection(slot: Int): SlotServiceConnection =
        connections.computeIfAbsent(slot) { index ->
            SlotServiceConnection(index).also { conn ->
                appContext?.let { conn.initialize(it) }
                if (index != ShizukuProcessServiceConnector.PRIMARY_SLOT) {
                    persistedSlots.add(index)
                    persistSlots()
                }
            }
        }

    /** 已建连接的路（升序），用于 UI 枚举 */
    val activeSlots: List<Int> get() = connections.keys.sorted()

    /**
     * 已知的全部实例路号（升序）：始终含 0 号路，加上持久化记录里建过的路。
     *
     * 与 [activeSlots] 的区别：这里只看「用户创建过哪些实例」的落盘记录，
     * 不要求对应进程此刻已 bind/已连接。定时触发这类可能在连接建立前就要枚举实例的场景用它。
     */
    val knownSlots: List<Int>
        get() {
            val persisted = prefs?.getString(KEY_SLOTS, null).orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > ShizukuProcessServiceConnector.PRIMARY_SLOT }
            return (listOf(ShizukuProcessServiceConnector.PRIMARY_SLOT) + persisted + connections.keys)
                .distinct()
                .sorted()
        }

    /** 已建连接则返回，不建新的；给 UI 按路订阅状态用 */
    fun slotConnectionOrNull(slot: Int): SlotServiceConnection? = connections[slot]

    /**
     * 申请一个空闲路号（0 号路保留给单路流程，不参与分配）。
     *
     * 取「最小的空闲号」而不是往后递增：删掉实例后号位会被回收，
     * 不会出现「删了五、新建变六」这种编号一路涨上去的情况
     *
     * 只发号，不建连接——真正拉起进程由调用方随后 [SlotServiceConnection.getInstance] 触发
     */
    fun allocateSlot(): Int {
        var candidate = ShizukuProcessServiceConnector.PRIMARY_SLOT + 1
        while (true) {
            // 连接表看的是本次进程内，持久化集合看的是上次启动留下的路号，两个都要避开
            val taken = connections.containsKey(candidate) || persistedSlots.contains(candidate)
            if (!taken) return candidate
            candidate++
        }
    }

    /**
     * 释放一路：清掉连接与残留进程，并从持久化里划掉。
     * 0 号路是主流程底座，不允许释放；正被操作的那一路释放后回落到 0 号路
     */
    fun releaseSlot(slot: Int) {
        if (slot == ShizukuProcessServiceConnector.PRIMARY_SLOT) {
            Timber.w("releaseSlot(%d) refused: primary slot is not releasable", slot)
            return
        }
        connections.remove(slot)?.release()
        persistedSlots.remove(slot)
        persistSlots()
        if (_activeSlot.value == slot) {
            _activeSlot.value = ShizukuProcessServiceConnector.PRIMARY_SLOT
            ActiveSlotProvider.set(ShizukuProcessServiceConnector.PRIMARY_SLOT)
        }
    }

    /** 把已建的路号写盘；[persistedSlots] 不含 0 号路 */
    private fun persistSlots() {
        val value = persistedSlots.sorted().joinToString(",")
        prefs?.edit()?.putString(KEY_SLOTS, value)?.apply()
    }

    /**
     * 恢复上次建过的路
     *
     * 只恢复编号与连接对象，进程重新拉起：app 重启后那些特权进程早已不在。
     * 失败不抛——某一路上不去不该拦住整个 app 启动，用户回设置页重开即可
     */
    private fun restoreSlots() {
        val saved = prefs?.getString(KEY_SLOTS, null).orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > ShizukuProcessServiceConnector.PRIMARY_SLOT }
            .distinct()
        if (saved.isEmpty()) return
        Timber.i("RemoteServiceManager: restoring slots %s", saved)
        for (slot in saved) {
            connection(slot)
            restoreScope.launch {
                runCatching { connection(slot).getInstance() }
                    .onFailure { Timber.w(it, "restore slot %d failed", slot) }
            }
        }
    }

    // ── PrivilegedServicePort：全部按「当前选中路」分发 ──

    /**
     * 用 flatMapLatest 跟住 [activeSlot]：切路后订阅自动挪到新路的连接上。
     * 做成稳定的 StateFlow 而不是 getter，订阅方（Compose / SessionViewModel）才不用重订
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val serviceState: StateFlow<PrivilegedServiceState> =
        _activeSlot
            .flatMapLatest { slot -> connection(slot).serviceState }
            .stateIn(
                facadeScope,
                SharingStarted.Eagerly,
                PrivilegedServiceState.Disconnected,
            )

    override val currentBackend: RemoteBackend? get() = active.currentBackend

    override fun bind() = active.bind()

    override fun unbind() = active.unbind()

    override fun serviceOrNull(): RemoteService? = active.serviceOrNull()

    /** 按路取服务面：执行器逐路对账要用，拿到的是指定路的面，不碰当前选中路 */
    override fun serviceOrNull(slot: Int): RemoteService? =
        slotConnectionOrNull(slot)?.serviceOrNull()

    override suspend fun <R> useService(action: suspend (RemoteService) -> R): R =
        active.useService(action)

    /** 按路投递：授权刷新/重绑/取值都在该路连接上，切路不影响它 */
    override suspend fun <R> useService(slot: Int, action: suspend (RemoteService) -> R): R =
        connection(slot).useService(action)

    // ── 既有扩展入口（同样跟着当前选中路） ──

    suspend fun getInstance(timeoutMs: Long? = null): RemoteService = active.getInstance(timeoutMs)

    /** 当前路的已连接后端；未连接返回 null */
    fun connectedBackendOrNull(): RemoteBackend? = active.connectedBackendOrNull()

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
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connection(ShizukuProcessServiceConnector.PRIMARY_SLOT).initialize(app)
        LogcatServiceManager.initialize(context)
        restoreSlots()
    }
}
