package com.aliothmoon.maafw.util

/**
 * 外壳要按 ROM 分支的那几处只认小米；读系统属性走反射，app 与特权进程两边都能用
 *
 * 不按 `Build.MANUFACTURER` 判：红米、POCO 与刷了别家 ROM 的小米机都对不上
 */
object RomInfo {

    /** MIUI 或 HyperOS */
    val isXiaomi: Boolean by lazy { isXiaomi(::systemProperty) }

    internal fun isXiaomi(prop: (String) -> String): Boolean =
        prop("ro.mi.os.version.name").isNotBlank() || prop("ro.miui.ui.version.name").isNotBlank()

    private val getter by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    /** 读不到（没有这个键、反射被拦）一律空串 */
    fun systemProperty(key: String): String =
        runCatching { getter?.invoke(null, key) as? String }.getOrNull().orEmpty()
}
