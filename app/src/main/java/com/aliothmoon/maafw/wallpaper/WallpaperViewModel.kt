package com.aliothmoon.maafw.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 自定义背景页：选图、裁剪落盘与三个滑块；数据都在 [WallpaperStore] */
class WallpaperViewModel(
    private val store: WallpaperStore,
    private val appSettings: AppSettingsManager,
) : ViewModel() {

    val settings: StateFlow<WallpaperSettings> = store.settings
    val image: StateFlow<ImageBitmap?> = store.image

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setWallpaperEnabled(enabled) }
    }

    fun setImageAlpha(percent: Int) {
        viewModelScope.launch { appSettings.setWallpaperImageAlpha(percent) }
    }

    fun setScrim(percent: Int) {
        viewModelScope.launch { appSettings.setWallpaperScrim(percent) }
    }

    fun setBlur(percent: Int) {
        viewModelScope.launch { appSettings.setWallpaperBlur(percent) }
    }

    fun remove() {
        viewModelScope.launch { store.clear() }
    }

    suspend fun prepareSource(uri: Uri): String? = store.prepareSource(uri)

    suspend fun decodeSource(path: String): Bitmap? = store.decodeSource(path)

    suspend fun saveCropped(bitmap: Bitmap): Boolean = store.saveCropped(bitmap)

    fun discardSource() = store.clearSourceCache()
}
