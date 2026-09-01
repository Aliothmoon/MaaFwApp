package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.remote.RemoteServiceImpl

object RootRemoteServiceConnector : ProcessServiceConnectorBackend(SuSpawner) {

    override val backend = RemoteBackend.ROOT
    override val eventPrefix = "ROOT"
    override val processNameSuffix = "root_service"
    override val serviceClass: Class<*> = RemoteServiceImpl::class.java
    override val logFileName = "root_launch_debug.log"
    override val keepRoot: Boolean get() = keepRootForInputInjection
}
