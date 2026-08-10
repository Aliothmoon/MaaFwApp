package com.aliothmoon.maafw.privileged

enum class ShizukuReadinessStage {
    /** 未安装 Shizuku，也没检测到 Sui */
    NotInstalled,

    /** 已安装但服务未启动 */
    NotRunning,

    /** 检测到 Sui（Magisk 模块）提供服务 */
    SuiAvailable,

    /** 服务运行中但未授权 */
    NeedAuth,

    /** 就绪：已授权、当前后端不是 Shizuku，或用户已选跳过 */
    Ready,
}

data class ShizukuReadiness(
    val stage: ShizukuReadinessStage = ShizukuReadinessStage.Ready,
    val canSwitchToRoot: Boolean = false,
) {
    val needsGuidance: Boolean
        get() = stage != ShizukuReadinessStage.Ready
}
