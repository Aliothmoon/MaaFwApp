package com.aliothmoon.maafw.notification

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.notification.live.LiveCapability
import com.aliothmoon.maafw.notification.live.LiveCapabilityProbe
import com.aliothmoon.maafw.notification.live.LiveChipContent
import com.aliothmoon.maafw.notification.live.LiveColorScheme
import com.aliothmoon.maafw.notification.live.LiveStyle
import com.aliothmoon.maafw.notification.live.LiveTrackerIcon
import com.aliothmoon.maafw.notification.live.TrackerIconStore
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 运行通知样式页：显示内容、进度条颜色、图标；数据都在 [AppSettingsManager.liveStyle] 与 [TrackerIconStore] */
class LiveStyleViewModel(
    private val appSettings: AppSettingsManager,
    private val trackerIcons: TrackerIconStore,
    liveProbe: LiveCapabilityProbe,
) : ViewModel() {

    val style: StateFlow<LiveStyle> = appSettings.liveStyle
    val icon: StateFlow<Bitmap?> = trackerIcons.icon

    private val _capability = MutableStateFlow<LiveCapability?>(null)

    /** 按实际生效的展示方式决定列哪几项；读出来之前为 null，页面先空着 */
    val capability: StateFlow<LiveCapability?> = _capability.asStateFlow()

    init {
        // 焦点通知权限是一次 ContentProvider 往返，不在主线程读
        viewModelScope.launch {
            _capability.value = withContext(MaaDispatchers.IO) { liveProbe.snapshot() }
        }
    }

    fun setChipContent(content: LiveChipContent) {
        viewModelScope.launch { appSettings.setLiveChipContent(content) }
    }

    fun setColorScheme(scheme: LiveColorScheme) {
        viewModelScope.launch { appSettings.setLiveColorScheme(scheme) }
    }

    fun setCustomColor(hex: String) {
        viewModelScope.launch { appSettings.setLiveCustomColor(hex) }
    }

    fun setTrackerIcon(icon: LiveTrackerIcon) {
        viewModelScope.launch { appSettings.setLiveTrackerIcon(icon) }
    }

    /** 不是图片或复制失败返回 false，图标不变 */
    suspend fun importCustomIcon(uri: Uri): Boolean = trackerIcons.importCustom(uri)

    fun clearCustomIcon() {
        viewModelScope.launch { trackerIcons.clearCustom() }
    }
}
