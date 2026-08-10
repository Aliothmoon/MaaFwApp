package com.aliothmoon.preferences

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrefKey(
    val name: String = "",     // key 名称，默认 camelCase -> snake_case
    val default: String = ""   // 默认值，根据字段类型自动转换
)
