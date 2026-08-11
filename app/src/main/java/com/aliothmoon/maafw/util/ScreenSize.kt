package com.aliothmoon.maafw.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs

/**
 * 主屏尺寸的读取与 16:9 换算（对齐 MaaMeow 的 `Misc` 同名几个函数）
 *
 * 与后台虚拟屏那套（`ResolutionPreference` / `DisplayResolution`）无关：那边是自己建的屏，
 * 尺寸由外壳指定；这里读的是物理主屏，前台模式在上面直接采集与注入
 *
 * 调用方是进程级组件（`DisplaySizeController`），手上只有 Application context，
 * 所以取屏幕的方式受限——见 [physical] 上的说明
 */
object ScreenSize {

    /** 长短边比与 16:9 的相对偏差上限；面板布局按 16:9 摆，差一点点不至于错位 */
    private const val ASPECT_TOLERANCE = 0.02f

    private const val UNIT_W = 16
    private const val UNIT_H = 9

    /** 16:9 换算的下限；再低就不够放下面板本身 */
    private const val MIN_LONG_SIDE = 1280
    private const val MIN_SHORT_SIDE = 720

    /**
     * 当前**生效**的主屏尺寸，含 `setForcedDisplaySize` 改过之后的值
     *
     * 不能用 `context.resources.displayMetrics`：Application 那份不反映强改后的尺寸
     * （实测 Android 9 上一直返回物理分辨率减系统栏），校验会永远判错
     */
    fun current(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * 出厂物理尺寸，**不受** `setForcedDisplaySize` 影响——它是硬件 mode 的分辨率
     *
     * 算 16:9 目标值要用它：拿改过的当前尺寸去算，连点两次「修改分辨率」会一路缩下去
     *
     * 经 [DisplayManager] 取 [Display] 而不是 `context.display`：后者会校验调用方是不是
     * visual context（Activity 或 `createWindowContext` 造的），拿 Application context
     * 调直接抛 `UnsupportedOperationException: Tried to obtain display from a Context not
     * associated with one`。MaaMeow 那边用 `context.display` 是因为它从 UI 把 Activity
     * context 传了下来，而这里的调用方在进程级，不该持有 Activity
     *
     * 取不到屏幕时返回 0×0，由 [fit16x9] 判成「换算无意义」
     */
    fun physical(context: Context): Pair<Int, Int> {
        val mode = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.mode
            ?: return 0 to 0
        return mode.physicalWidth to mode.physicalHeight
    }

    /** 按长短边比判，与横竖屏无关 */
    fun isAspect16x9(width: Int, height: Int): Boolean {
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (shortSide <= 0) return false
        val target = UNIT_W.toFloat() / UNIT_H
        return abs(longSide.toFloat() / shortSide - target) <= target * ASPECT_TOLERANCE
    }

    /**
     * 物理尺寸内能放下的最大 16:9，方向跟随原屏
     *
     * 取 16 与 9 的整倍数而不是按边裁：非整倍数的宽高在部分 ROM 上会被 SurfaceFlinger
     * 再对齐一次，落到的实际尺寸与请求的对不上
     *
     * @return null 表示屏幕太小或读不到物理尺寸，换算无意义
     */
    fun fit16x9(physicalWidth: Int, physicalHeight: Int): Pair<Int, Int>? {
        if (physicalWidth <= 0 || physicalHeight <= 0) return null

        val landscape = physicalWidth >= physicalHeight
        val maxLong = if (landscape) physicalWidth else physicalHeight
        val maxShort = if (landscape) physicalHeight else physicalWidth
        if (maxLong < MIN_LONG_SIDE || maxShort < MIN_SHORT_SIDE) return null

        val scale = minOf(maxLong / UNIT_W, maxShort / UNIT_H)
        val longSide = UNIT_W * scale
        val shortSide = UNIT_H * scale
        return if (landscape) longSide to shortSide else shortSide to longSide
    }
}
