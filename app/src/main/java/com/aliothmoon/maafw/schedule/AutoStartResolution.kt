package com.aliothmoon.maafw.schedule

/**
 * 自启动引导的纯决策逻辑，与 Android Intent 解析解耦，便于单测
 */
sealed interface AutoStartTarget {
    /** [id] 对应 [AutoStartHelper.OemEntry.id] */
    data class Oem(val id: String) : AutoStartTarget

    /** 兜底：任何 ROM 都能打开 */
    data object AppDetails : AutoStartTarget
}

object AutoStartResolution {

    /** 厂商页可解析则跳厂商页，受限厂商解析不到退应用详情页，其余不引导 */
    fun select(
        resolvableOemIds: List<String>,
        knownRestrictiveManufacturer: Boolean,
    ): AutoStartTarget? = when {
        resolvableOemIds.isNotEmpty() -> AutoStartTarget.Oem(resolvableOemIds.first())
        knownRestrictiveManufacturer -> AutoStartTarget.AppDetails
        else -> null
    }
}
