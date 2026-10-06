package com.aliothmoon.maafw.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.util.ScreenSize
import com.aliothmoon.maafw.util.orientByExif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** 自定义背景的设置项；百分比都已夹在 0–100 */
data class WallpaperSettings(
    val enabled: Boolean = false,
    /** 换图令牌：图片文件名固定，靠它变化触发重新解码 */
    val token: String = "",
    val imageAlpha: Int = 80,
    val scrim: Int = 25,
    val blur: Int = 0,
)

/**
 * 自定义背景图的唯一数据源（移植自 MaaMeow 的 BackgroundImageStore）
 *
 * - [prepareSource] / [decodeSource]：选中的图先复制进缓存再按 EXIF 摆正解码，给裁剪页用；
 *   进程被杀重建后图片选择器的 Uri 授权通常已失效，只能从缓存文件恢复
 * - [saveCropped]：裁剪结果写 filesDir/backgrounds/bg.jpg，换令牌并启用
 * - [clear]：删文件并关闭
 * - [image]：盯着「开关 + 令牌」，在 IO 线程按屏幕尺寸降采样解码
 *
 * 只管数据与解码；玻璃配色与绘制在 theme 层
 */
class WallpaperStore(
    private val context: Context,
    private val appSettings: AppSettingsManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + MaaDispatchers.IO)

    // 保存是 NonCancellable 的，转屏后可能还在跑；重建出来的页面再存一次会与它共写同一个临时文件
    private val writeMutex = Mutex()

    private val directory: File get() = File(context.filesDir, DIR_NAME)
    private val file: File get() = File(directory, FILE_NAME)

    val settings: StateFlow<WallpaperSettings> = appSettings.wallpaper

    /** 当前生效的背景；没开或没有文件时为 null */
    @OptIn(ExperimentalCoroutinesApi::class)
    val image: StateFlow<ImageBitmap?> = appSettings.wallpaper
        .map { it.enabled to it.token }
        .distinctUntilChanged()
        .mapLatest { (enabled, _) -> if (enabled) load() else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun prepareSource(uri: Uri): String? = withContext(MaaDispatchers.IO) {
        runCatching {
            val source = File(context.cacheDir, SOURCE_TMP_NAME)
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            source.absolutePath
        }.onFailure { Timber.e(it, "Failed to copy the picked wallpaper") }.getOrNull()
    }

    /** 按 EXIF 方向摆正，长边限制在 [MAX_SOURCE_SIDE] 内；读不到 EXIF 按正常方向 */
    suspend fun decodeSource(path: String): Bitmap? = withContext(MaaDispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > MAX_SOURCE_SIDE || bounds.outHeight / sample > MAX_SOURCE_SIDE) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            orientByExif(path, decoded)
        }.onFailure { Timber.e(it, "Failed to decode the picked wallpaper") }.getOrNull()
    }

    fun clearSourceCache() {
        scope.launch { runCatching { File(context.cacheDir, SOURCE_TMP_NAME).delete() } }
    }

    suspend fun saveCropped(bitmap: Bitmap): Boolean = withContext(NonCancellable + MaaDispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                directory.mkdirs()
                // 先写临时文件再同目录改名：压缩失败或进程被杀都不会弄坏已有的背景
                val temporary = File(directory, "$FILE_NAME.tmp")
                try {
                    temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                    check(temporary.renameTo(file)) { "Failed to move the wallpaper into place" }
                } finally {
                    temporary.delete()
                }
                appSettings.setWallpaperState(enabled = true, token = System.currentTimeMillis().toString())
                true
            }.onFailure { Timber.e(it, "Failed to save the wallpaper") }.getOrDefault(false)
        }
    }

    suspend fun clear() = withContext(MaaDispatchers.IO) {
        writeMutex.withLock {
            appSettings.setWallpaperState(enabled = false, token = "")
            runCatching { file.delete() }
            runCatching { File(directory, "$FILE_NAME.tmp").delete() }
        }
    }

    private fun load(): ImageBitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (screenWidth, screenHeight) = ScreenSize.current(context)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, screenWidth, screenHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap() }
            .onFailure { Timber.e(it, "Failed to load the wallpaper") }
            .getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
        if (requestedWidth <= 0 || requestedHeight <= 0) return 1
        var sample = 1
        while (width / 2 / sample >= requestedWidth && height / 2 / sample >= requestedHeight) sample *= 2
        return sample
    }

    private companion object {
        const val DIR_NAME = "backgrounds"
        const val FILE_NAME = "bg.jpg"
        const val SOURCE_TMP_NAME = "bg_source_tmp"
        const val MAX_SOURCE_SIDE = 2400
    }
}
