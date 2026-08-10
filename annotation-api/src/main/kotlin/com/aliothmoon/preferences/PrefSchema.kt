package com.aliothmoon.preferences

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PrefSchema(
    val name: String = ""  // Schema 名称前缀，默认使用类名
)
