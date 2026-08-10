package com.aliothmoon.maafw.domain

/**
 * 前台模式下怎么唤起控制面板
 *
 * 只在 [RunMode.FOREGROUND] 有意义：后台虚拟屏模式下 app 界面本来就在，不需要控制层
 */
enum class OverlayControlMode {
    /** 同时按音量 ±；屏幕上零占用，但要无障碍服务 */
    ACCESSIBILITY,

    /** 常驻悬浮球；所见即所得，但会盖住一小块画面 */
    FLOAT_BALL,
}
