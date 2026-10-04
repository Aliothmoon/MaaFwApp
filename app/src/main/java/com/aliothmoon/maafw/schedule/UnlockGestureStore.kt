package com.aliothmoon.maafw.schedule

import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.domain.UnlockGestureJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** 解锁 hook 只要 JSON，抽出来好在单测里换掉 */
fun interface UnlockGestureReader {
    /** 没录过返回空串 */
    suspend fun readJson(): String
}

/**
 * 录制的解锁手势（移植自 MaaMeow）
 *
 * 单独落文件而不进 DataStore：轨迹有几 KB，塞进 AppSettings 会让每次设置变更都跟着解析一遍；
 * 落在 app 私有目录也顺带保证它不会随配置导出——轨迹等价于锁屏凭证。
 * 特权进程读不到这个目录，回放时由 app 把 JSON 经 binder 递过去
 */
class UnlockGestureStore(private val context: Context) : UnlockGestureReader {

    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)
    private val writeMutex = Mutex()

    private val gestureFile: File
        get() = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    private val _gesture = MutableStateFlow<UnlockGesture?>(null)

    /** 已录制的手势；未录制为 null */
    val gesture: StateFlow<UnlockGesture?> = _gesture.asStateFlow()

    init {
        scope.launch { _gesture.value = read() }
    }

    suspend fun save(gesture: UnlockGesture) {
        writeMutex.withLock {
            withContext(MaaDispatchers.IO) {
                runCatching {
                    val file = gestureFile
                    file.parentFile?.mkdirs()
                    file.writeText(UnlockGestureJson.encodeToString(UnlockGesture.serializer(), gesture))
                }.onFailure { Timber.e(it, "save unlock gesture failed") }
            }
        }
        _gesture.value = gesture
    }

    suspend fun clear() {
        writeMutex.withLock {
            withContext(MaaDispatchers.IO) {
                runCatching { gestureFile.delete() }
                    .onFailure { Timber.w(it, "clear unlock gesture failed") }
            }
        }
        _gesture.value = null
    }

    override suspend fun readJson(): String = withContext(MaaDispatchers.IO) {
        val gesture = _gesture.value ?: read() ?: return@withContext ""
        UnlockGestureJson.encodeToString(UnlockGesture.serializer(), gesture)
    }

    private suspend fun read(): UnlockGesture? = withContext(MaaDispatchers.IO) {
        val file = gestureFile
        if (!file.isFile) return@withContext null
        val text = runCatching { file.readText() }.getOrElse {
            Timber.w(it, "unlock gesture unreadable")
            return@withContext null
        }
        UnlockGesture.parseOrNull(text) { Timber.w(it) }
    }

    private companion object {
        const val DIR_NAME = "unlock"
        const val FILE_NAME = "gesture.json"
    }
}
