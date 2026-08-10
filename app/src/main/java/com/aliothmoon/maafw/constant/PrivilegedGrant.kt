package com.aliothmoon.maafw.constant

/**
 * 特权进程代授的权限位；app 侧与特权进程侧共用
 *
 * 只列 manifest 里真正声明的：MaaMeow 还会授悬浮窗/无障碍/存储，那对应它的前台模式，
 * 本项目没有对应功能，也没声明那些权限，代授等于替用户要没申请过的权限
 *
 * 位值只增不改，与 AIDL 的 transaction id 同理：app 升级后旧特权进程可能仍存活
 */
object PrivilegedGrant {
    const val NOTIFICATION = 1 shl 0
    const val BATTERY = 1 shl 1

    /** 后台不受限：standby bucket、bg-restriction、AppOps 与 Phantom Process Killer 一并处理 */
    const val BACKGROUND = 1 shl 2

    const val ALL = NOTIFICATION or BATTERY or BACKGROUND
}
