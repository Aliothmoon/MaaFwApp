package com.aliothmoon.maafw.privileged

import android.os.IBinder
import com.aliothmoon.maafw.domain.RemoteBackend

interface RemoteServiceConnectorBackend {
    val backend: RemoteBackend

    fun connect(callbacks: Callbacks)

    fun disconnect(currentBinder: IBinder?)

    interface Callbacks {
        fun onConnected(backend: RemoteBackend, binder: IBinder)

        fun onDisconnected(backend: RemoteBackend)

        fun onError(backend: RemoteBackend, throwable: Throwable)
    }
}
