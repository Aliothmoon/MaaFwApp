package com.aliothmoon.maafw.constant

/**
 * 特权进程代授的权限位；app 侧与特权进程侧共用
 *
 * 位值只增不改，与 AIDL 的 transaction id 同理：app 升级后旧特权进程可能仍存活
 */
object PrivilegedGrant {
    const val NOTIFICATION = 1 shl 0
    const val BATTERY = 1 shl 1

    /** 后台不受限：standby bucket、bg-restriction、AppOps 与 Phantom Process Killer 一并处理 */
    const val BACKGROUND = 1 shl 2

    /** 悬浮窗（OP_SYSTEM_ALERT_WINDOW）；前台模式的控制层要它 */
    const val OVERLAY = 1 shl 3

    /**
     * 无障碍：直接写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     *
     * 只有前台模式的音量键唤起用得上，且必须随 `accessibilityServiceId` 一起传——
     * 服务 id 是 app 侧的组件名，特权进程拼不出来
     */
    const val ACCESSIBILITY = 1 shl 4

    /** 保活三件套；悬浮窗与无障碍按运行模式单独要，不进这里 */
    const val ALL = NOTIFICATION or BATTERY or BACKGROUND
}
