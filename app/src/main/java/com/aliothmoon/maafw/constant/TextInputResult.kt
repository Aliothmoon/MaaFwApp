package com.aliothmoon.maafw.constant

/** 用整型而不是枚举过 binder：app 升级但旧特权进程仍存活时，两边的枚举布局可能对不上 */
object TextInputResult {
    const val OK = 0

    const val SERVICE_UNAVAILABLE = 1

    /** Android 11 以下无障碍拿不到非主屏的窗口 */
    const val UNSUPPORTED_DISPLAY = 2

    const val NO_FOCUSED_INPUT = 3

    const val FOREIGN_PACKAGE = 4

    const val ACTION_FAILED = 5
}
