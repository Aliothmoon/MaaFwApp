package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.util.RomInfo

/**
 * 小米「USB 调试（安全设置）」：关着时 shell 身份的触控输入会被系统拦截
 * 运行时读属性即生效；App 进程就能读，不依赖特权进程
 */
internal object XiaomiAdbInput {

    fun isRestricted(): Boolean =
        RomInfo.isXiaomi && RomInfo.systemProperty("persist.security.adbinput") != "1"
}
