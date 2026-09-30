package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.BuildConfig

/**
 * 构建期闸门：外壳的 debug 构建一律不上报，免得把本地联调的数据混进 PI 作者的看板
 *
 * 对齐 MXU 的 `cfg!(debug_assertions)`；看的是外壳自己的构建，不看 PI 的 `version`——
 * PI 版本号由作者随手打，拿它当闸门会把正式分发的 0.x 项目整个挡在外面
 */
val isTelemetryBlockedByBuild: Boolean get() = BuildConfig.DEBUG
