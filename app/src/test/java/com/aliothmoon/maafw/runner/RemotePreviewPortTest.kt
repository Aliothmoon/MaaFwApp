package com.aliothmoon.maafw.runner

import android.view.Surface
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePreviewPortTest {

    private class SurfaceRecordingService : FakePrivilegedService() {
        val surfaces = mutableListOf<Surface?>()

        override fun setMonitorSurface(surface: Surface?) {
            surfaces += surface
        }
    }

    private fun TestScope.port(servicePort: FakePrivilegedServicePort) =
        RemotePreviewPort(backgroundScope, servicePort, MutableStateFlow(false)).also { runCurrent() }

    /** 模拟特权进程换了一个：断开，再带着新的服务面连上 */
    private fun TestScope.reconnect(servicePort: FakePrivilegedServicePort, next: FakePrivilegedService) {
        servicePort.service = null
        servicePort.emit(PrivilegedServiceState.Disconnected)
        runCurrent()
        servicePort.service = next
        servicePort.emit(PrivilegedServiceState.Connected)
        runCurrent()
    }

    @Test
    fun `a surface that arrives before the service is sent once it connects`() = runTest {
        val service = SurfaceRecordingService()
        val servicePort = FakePrivilegedServicePort(service = null).apply {
            emit(PrivilegedServiceState.Disconnected)
        }
        val port = port(servicePort)
        val surface = mockk<Surface>()

        port.attachSurface(surface)
        assertEquals(emptyList<Surface?>(), service.surfaces)

        reconnect(servicePort, service)

        assertEquals(listOf<Surface?>(surface), service.surfaces)
        assertEquals(0, port.surfaceEpoch.value)
    }

    @Test
    fun `a surface already handed to one service is not sent to the next`() = runTest {
        val first = SurfaceRecordingService()
        val second = SurfaceRecordingService()
        val servicePort = FakePrivilegedServicePort(first)
        val port = port(servicePort)
        val surface = mockk<Surface>()
        port.attachSurface(surface)

        reconnect(servicePort, second)

        assertEquals(emptyList<Surface?>(), second.surfaces)
        assertEquals(1, port.surfaceEpoch.value)
    }

    @Test
    fun `the replacement surface goes to the new service`() = runTest {
        val first = SurfaceRecordingService()
        val second = SurfaceRecordingService()
        val servicePort = FakePrivilegedServicePort(first)
        val port = port(servicePort)
        port.attachSurface(mockk())
        reconnect(servicePort, second)

        // UI 按 surfaceEpoch 重建 SurfaceView：旧的先销毁，新的再上报
        val replacement = mockk<Surface>()
        port.detachSurface()
        port.attachSurface(replacement)

        assertEquals(listOf<Surface?>(null, replacement), second.surfaces)
        assertEquals(1, port.surfaceEpoch.value)
    }

    @Test
    fun `reconnecting without a surface asks for nothing`() = runTest {
        val servicePort = FakePrivilegedServicePort(SurfaceRecordingService())
        val port = port(servicePort)

        reconnect(servicePort, SurfaceRecordingService())

        assertEquals(0, port.surfaceEpoch.value)
    }
}
