package com.aliothmoon.maafw.overlay.border

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 边框视图的窗口挂载
 *
 * 不走 FloatingX：那个库管的是可拖拽的浮窗，而边框要的恰恰是**不可触摸、不可聚焦、
 * 铺满整屏**——直接用 WindowManager 更短也更可控
 */
class BorderOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: BorderOverlayView? = null
    private var currentStyle: BorderStyle = BorderStyle()

    suspend fun show(style: BorderStyle = BorderStyle()) {
        if (overlayView != null) {
            if (currentStyle == style) return
            hide()
        }
        currentStyle = style
        runCatching {
            val view = BorderOverlayView(context, style)
            withContext(Dispatchers.Main) { windowManager.addView(view, createLayoutParams()) }
            overlayView = view
            Timber.d("运行边框已显示")
        }.onFailure {
            Timber.e(it, "运行边框显示失败")
            overlayView = null
        }
    }

    suspend fun hide() {
        val view = overlayView ?: return
        overlayView = null
        runCatching { withContext(Dispatchers.Main) { windowManager.removeView(view) } }
            .onFailure { Timber.e(it, "运行边框移除失败") }
    }

    fun isShowing(): Boolean = overlayView != null

    /**
     * 不可聚焦 + 不可触摸：边框只是提示，任何触摸都要原样落到下面的目标应用
     * NO_LIMITS + SHORT_EDGES 让它盖到挖孔与圆角之外，否则边框会被状态栏区域截断
     */
    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
