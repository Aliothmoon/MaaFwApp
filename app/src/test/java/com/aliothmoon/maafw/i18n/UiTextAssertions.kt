package com.aliothmoon.maafw.i18n

import androidx.annotation.StringRes

/**
 * 纯 JVM 单测拿不到 Context，解析不出文案，只能比资源 id 与参数
 *
 * [args] 省略即只比 id（「出没出这条诊断」），给了就连参数一起比（「诊断指向哪个对象」）
 */
fun UiText?.isResource(@StringRes resId: Int, vararg args: Any?): Boolean {
    val resource = this as? UiText.Resource ?: return false
    if (resource.resId != resId) return false
    if (args.isEmpty()) return true
    return resource.args == args.toList()
}

/** 断言失败时能看出实际是哪条资源，比 `assertTrue(false)` 有用 */
fun UiText?.describe(): String = when (this) {
    null -> "null"
    UiText.Empty -> "Empty"
    is UiText.Dynamic -> "Dynamic(${value})"
    is UiText.Resource -> "Resource(id=$resId, args=$args)"
    is UiText.Joined -> "Joined(${parts.joinToString { it.describe() }})"
}
