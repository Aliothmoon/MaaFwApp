package com.aliothmoon.maafw.util

/** UserHandle.PER_USER_RANGE 是隐藏常量：uid 按它分段，商就是 userId */
private const val PER_USER_RANGE = 100_000

internal fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE
