package com.aliothmoon.maafw.privileged

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 特权进程能否拉起只有真机能验：要么装了 Shizuku 并授过权，要么设备有 root
 * 两个条件都不满足时跳过，不算失败
 */
@RunWith(AndroidJUnit4::class)
class RemoteServiceConnectionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connectOrSkip(backend: RemoteBackend) = runBlocking {
        RemoteServiceManager.initialize(context) { backend }
        // Shizuku 的 binder 由 ShizukuProvider 异步送达，初始化后不是立刻可用
        var available = false
        repeat(20) {
            if (RemoteAccessCoordinator.refresh().isAvailable(backend)) {
                available = true
                return@repeat
            }
            Thread.sleep(500)
        }
        assumeTrue("$backend 不可用", available)
        assumeTrue("$backend 未授权", RemoteAccessCoordinator.request(backend))

        RemoteServiceManager.unbind()
        val service = RemoteServiceManager.getInstance(timeoutMs = 30_000)
        val version = service.version()
        val pid = service.pid()
        // 版本串里带 bridge ping，说明 libbridge.so 在特权进程里加载成功
        assertTrue("version 应包含 bridge 信息: $version", version.contains("bridge="))
        assertTrue("特权进程 pid 应有效: $pid", pid > 0)
        assertTrue("bridge 未加载: $version", !version.contains("not loaded"))
        version
    }

    @Test
    fun shizukuBackendConnects() {
        val version = connectOrSkip(RemoteBackend.SHIZUKU)
        println("[shizuku] $version")
    }

    @Test
    fun rootBackendConnects() {
        val version = connectOrSkip(RemoteBackend.ROOT)
        println("[root] $version")
    }
}
