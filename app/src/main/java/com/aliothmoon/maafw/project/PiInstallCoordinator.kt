package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** PI 解包的可观测状态；除 [Unpacking] 外几档都是一闪而过，留着是为了状态机完整 */
sealed interface PiInstallState {
    data object NotChecked : PiInstallState

    /** 比对标记的那一下；比一个字符串，快到看不见 */
    data object Checking : PiInstallState

    data class Unpacking(
        val done: Int,
        val total: Int,
        val currentPath: String = "",
    ) : PiInstallState {
        val percent: Int get() = if (total > 0) done * 100 / total else 0
    }

    data object Ready : PiInstallState

    data class Failed(val reason: UiText) : PiInstallState
}

/**
 * PI 解包的唯一发起点
 *
 * 从 `ProjectSource` 的读取路径里挪出来的：解包是几十 MB 的搬运，藏在 `read("interface.json")`
 * 背后既没有进度可看，失败还会被记成「interface.json 读取失败」——那个文件本身好好的
 *
 * 调用方须先等到 [PiInstallState.Ready] 再去 `ProjectRepository.reload()`
 */
class PiInstallCoordinator(private val installer: PiInstaller) {

    private val _state = MutableStateFlow<PiInstallState>(PiInstallState.NotChecked)
    val state: StateFlow<PiInstallState> = _state.asStateFlow()

    // 解包本身在 PiInstaller 里是 @Synchronized 的；这把锁管的是状态迁移不交错
    private val mutex = Mutex()

    /** 已就绪直接返回，标记对不上才真解包 */
    suspend fun ensureInstalled(): Boolean = install(force = false)

    /** 不看标记的重解；设置页手动重来与失败重试都走这条 */
    suspend fun reinstall(): Boolean = install(force = true)

    private suspend fun install(force: Boolean): Boolean = mutex.withLock {
        if (!force && _state.value == PiInstallState.Ready) return@withLock true
        _state.value = PiInstallState.Checking
        try {
            withContext(MaaDispatchers.IO) {
                // 逐条目写状态，几千项的 PI 就是几千次 SessionUiState 重建，
                // 而这几秒恰好是首屏最挤的时候。百分比没变就不写，上千次压到 100 次以内
                // 解包线程池并发调这里，用 CAS 让只跨过档位的那一次过
                val lastPercent = AtomicInteger(-1)
                val onProgress: PiUnpackProgress = { done, total, path ->
                    val percent = if (total > 0) done * 100 / total else 0
                    val previous = lastPercent.get()
                    if (percent > previous && lastPercent.compareAndSet(previous, percent)) {
                        _state.value = PiInstallState.Unpacking(done, total, path)
                    }
                }
                if (force) installer.reinstall(onProgress) else installer.ensureInstalled(onProgress)
            }
            _state.value = PiInstallState.Ready
            true
        } catch (e: CancellationException) {
            // 作用域被取消不是解包失败，状态留在原处交给下一次
            throw e
        } catch (e: Exception) {
            _state.value = PiInstallState.Failed(uiTextOf(R.string.pi_install_error, e.message.orEmpty()))
            false
        }
    }
}
