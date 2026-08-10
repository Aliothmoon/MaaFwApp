package com.aliothmoon.maafw.domain

/**
 * 特权进程的提权后端
 *
 * 放 domain 而非 privileged：它是用户设置的一项，要进 [UserConfiguration]，
 * domain 不能反过来依赖基础设施层
 */
enum class RemoteBackend(val display: String) {
    SHIZUKU(display = "Shizuku"),
    ROOT(display = "Root"),
}
