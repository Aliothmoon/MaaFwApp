package com.aliothmoon.maafw.runner

import android.content.res.Resources

/**
 * [PhysicalDisplaySource] 的设备实现：按当前旋转取物理屏尺寸
 *
 * 只供 UI 摆预览与推导虚拟屏尺寸用。执行侧主屏模式的 screen_resolution 不走这里——
 * 特权进程的采集器自己读 DisplayInfo，两边算法不同（这里拿不到挖孔与 overscan 的修正），
 * 必须以采集器那份为准
 */
object SystemPhysicalDisplaySource : PhysicalDisplaySource {
    override fun current(): DisplayResolution {
        val metrics = Resources.getSystem().displayMetrics
        return DisplayResolution(metrics.widthPixels.alignEven(), metrics.heightPixels.alignEven())
    }

    private fun Int.alignEven(): Int = this and 1.inv()
}
