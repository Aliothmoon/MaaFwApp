package com.aliothmoon.maafw.constant

import java.io.File

/**
 * /data/local/tmp 下的固定路径
 *
 * shell 与 root 身份都属主可写可执行；外部私有目录挂 noexec、app 私有目录 shell 进不去，
 * agent 运行时与跨进程标志文件只能落这里
 */
object ShellDirs {
    private const val BASE = "/data/local/tmp"

    /** agent 运行时解包根，其下按 ABI 分（maafw-agent/<abi>） */
    val AGENT_DIR = File("$BASE/maafw-agent")

    /** 屏幕电源状态标志，PowerController 跨进程读写 */
    val POWER_OFF_FLAG = File("$BASE/maa_power_off_flag")

    /** 强制分辨率状态标志，ScreenManager 跨进程读写 */
    val SCREEN_FLAG = File("$BASE/maa_screen_flag")

    /** JNA 解压临时目录：app 私有目录 shell 身份写不进，指到 shell 可写位置 */
    const val JNA_TMPDIR = BASE
}
