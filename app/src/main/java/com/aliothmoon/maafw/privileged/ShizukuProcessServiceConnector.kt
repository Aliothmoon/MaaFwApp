package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.remote.RemoteServiceImpl

/**
 * Shizuku 唯一路径：newProcess 拉起自研 starter，见 [ShizukuSpawner]
 *
 * [slot] = [PRIMARY_SLOT] 时沿用历史进程名与日志名，单路行为与旧版逐字一致；
 * slot > 0 时进程名、日志名带上序号，拉起的是另一个完全独立的服务进程，
 * 于是同一个 App 里可以并存多路——每路的 native 状态、虚拟屏、MaaFramework 句柄
 * 都关在各自进程内，天然互不干扰
 */
class ShizukuProcessServiceConnector(private val slot: Int = PRIMARY_SLOT) :
    ProcessServiceConnectorBackend(ShizukuSpawner) {

    override val backend = RemoteBackend.SHIZUKU
    override val eventPrefix = withSlot("SHIZUKU")
    override val processNameSuffix = withSlot("shizuku_service")
    override val serviceClass: Class<*> = RemoteServiceImpl::class.java
    override val logFileName = withSlot("shizuku_launch_debug") + ".log"

    // 进程侧失败由 alive 轮询秒级暴露，超时只兜"进程活着但不回投"；实测全链 <1s，留 10 倍余量
    override val spawnTimeoutMs: Long = 10_000L

    // root 模式 Shizuku 与 root 后端同规则；adb 模式下 launcher 已是 shell，标志自动无效
    override val keepRoot: Boolean get() = keepRootForInputInjection

    private fun withSlot(base: String): String =
        if (slot == PRIMARY_SLOT) base else "${base}_$slot"

    companion object {
        /** 0 号路：沿用历史进程名，残留清理与旧日志路径都能对上 */
        const val PRIMARY_SLOT = 0
    }
}
