package com.aliothmoon.maafw.privileged

/** 特权进程的两条拉起路径；同一时刻只连其中一个（docs/privileged-runtime.md §3） */
enum class RemoteBackend(val display: String) {
    SHIZUKU(display = "Shizuku"),
    ROOT(display = "Root"),
}
