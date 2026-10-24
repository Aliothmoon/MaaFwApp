package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.remote.RemoteServiceImpl

/**
 * Root（su）路径：与 [ShizukuProcessServiceConnector] 同构，按 [slot] 区分进程名与日志名
 */
class RootRemoteServiceConnector(private val slot: Int = ShizukuProcessServiceConnector.PRIMARY_SLOT) :
    ProcessServiceConnectorBackend(SuSpawner) {

    override val backend = RemoteBackend.ROOT
    override val eventPrefix = withSlot("ROOT")
    override val processNameSuffix = withSlot("root_service")
    override val serviceClass: Class<*> = RemoteServiceImpl::class.java
    override val logFileName = withSlot("root_launch_debug") + ".log"
    override val keepRoot: Boolean get() = keepRootForInputInjection

    private fun withSlot(base: String): String =
        if (slot == ShizukuProcessServiceConnector.PRIMARY_SLOT) base else "${base}_$slot"
}
