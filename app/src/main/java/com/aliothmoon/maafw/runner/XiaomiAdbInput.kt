package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.keepRootForInputInjection
import com.aliothmoon.maafw.util.RomInfo

/**
 * 小米「USB 调试（安全设置）」：关着时 shell 身份的触控输入会被系统拦截
 * 运行时读属性即生效；App 进程就能读，不依赖特权进程
 */
internal object XiaomiAdbInput {

    fun isRestricted(servicePort: PrivilegedServicePort): Boolean =
        RomInfo.isXiaomi && RomInfo.systemProperty("persist.security.adbinput") != "1" &&
            // 14+ Root 后端特权进程留着 root，注入不受这一项管；两个后端都降到 shell 之后这一条随之删
            !(servicePort.currentBackend == RemoteBackend.ROOT && keepRootForInputInjection)
}
