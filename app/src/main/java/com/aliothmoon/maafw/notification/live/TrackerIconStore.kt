package com.aliothmoon.maafw.notification.live

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.util.LauncherApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** 运行通知的图标：按设置产出位图，并管理自定义图片文件（移植自 MaaMeow） */
class TrackerIconStore(
    context: Context,
    private val appSettings: AppSettingsManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)
    private val fileMutex = Mutex()

    private val iconDir: File
        get() = File(appContext.filesDir, ICON_DIR)

    /** 当前图标；DEFAULT 或解码失败为 null，由调用方用各自的默认图标。mapLatest 丢弃过期解码 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val icon: StateFlow<Bitmap?> = appSettings.liveStyle
        .map { it.trackerIcon to it.customTrackerPath }
        .distinctUntilChanged()
        .mapLatest { (type, path) -> load(type, path) }
        .stateIn(scope, SharingStarted.Eagerly, null)


    /** 导入选中的图片；不是图片或复制失败返回 false，设置不变 */
    suspend fun importCustom(uri: Uri): Boolean = withContext(MaaDispatchers.IO) {
        fileMutex.withLock {
            val file = copyToIconDir(uri) ?: return@withLock false
            if (!TrackerIconDecoder.isImage(file.path)) {
                file.delete()
                return@withLock false
            }
            // 先切新路径再删旧图，设置不会指向已删文件；文件名带时间戳，同图重选也会换路径
            appSettings.setLiveCustomTrackerPath(file.absolutePath)
            iconDir.listFiles()?.forEach { if (it != file) it.delete() }
            true
        }
    }

    /** 清除自定义图片并回落默认图标 */
    suspend fun clearCustom() = withContext(MaaDispatchers.IO) {
        fileMutex.withLock {
            appSettings.clearLiveCustomTracker()
            iconDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun load(type: LiveTrackerIcon, path: String): Bitmap? = when (type) {
        LiveTrackerIcon.DEFAULT -> null
        // 按通知尺寸栅格化：原图尺寸的位图每次 notify 都要跨进程传
        LiveTrackerIcon.APP -> LauncherApps.icon(appContext, appContext.packageName, TrackerIconDecoder.TARGET_SIZE)
        LiveTrackerIcon.CUSTOM -> TrackerIconDecoder.decode(path)
    }

    private fun copyToIconDir(uri: Uri): File? {
        val target = File(iconDir.apply { mkdirs() }, "$ICON_PREFIX${System.currentTimeMillis()}")
        val copied = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Timber.e(it, "Failed to copy the custom tracker icon") }.getOrNull()
        if (copied == null || copied == 0L) {
            target.delete()
            return null
        }
        return target
    }

    private companion object {
        const val ICON_DIR = "live_icon"
        const val ICON_PREFIX = "tracker_icon_"
    }
}
