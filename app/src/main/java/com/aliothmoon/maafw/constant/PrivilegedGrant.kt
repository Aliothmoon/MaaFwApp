package com.aliothmoon.maafw.constant

/**
 * 特权进程代授的权限位；app 侧与特权进程侧共用
 *
 * 位值只增不改，与 AIDL 的 transaction id 同理：app 升级后旧特权进程可能仍存活
 *
 * 集合对齐 MaaMeow 的 PERM_ALL：特权进程上线即把全集代授一遍，不再按运行模式挑——
 * 用不上的位代授是空操作，少授反而会在用户切模式时漏掉某一项
 */
object PrivilegedGrant {
    const val NOTIFICATION = 1 shl 0
    const val BATTERY = 1 shl 1

    /** 后台不受限：standby bucket、bg-restriction、AppOps 与 Phantom Process Killer 一并处理 */
    const val BACKGROUND = 1 shl 2

    /** 悬浮窗（OP_SYSTEM_ALERT_WINDOW）；前台控制层与后台屏保都要 */
    const val OVERLAY = 1 shl 3

    /**
     * 无障碍：直接写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     *
     * 服务 id 是 app 侧组件名，特权进程拼不出来，所以由 RemoteServiceImpl 直接引用常量
     */
    const val ACCESSIBILITY = 1 shl 4

    /** 存储访问（OP_MANAGE_EXTERNAL_STORAGE；旧版回退读写外部存储） */
    const val STORAGE = 1 shl 5

    /** 全集；特权进程上线即全代授，对齐 MaaMeow */
    const val ALL = NOTIFICATION or BATTERY or BACKGROUND or OVERLAY or ACCESSIBILITY or STORAGE
}
