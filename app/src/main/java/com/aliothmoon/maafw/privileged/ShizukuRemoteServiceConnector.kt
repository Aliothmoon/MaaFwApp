package com.aliothmoon.maafw.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.privileged.RemoteBackend
import com.aliothmoon.maafw.remote.RemoteServiceImpl
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object ShizukuRemoteServiceConnector : RemoteServiceConnectorBackend {

    override val backend: RemoteBackend = RemoteBackend.SHIZUKU

    private val serviceTag = UUID.randomUUID().toString()
    private val serviceVersion = AtomicInteger(100)

    @Volatile
    private var activeBinding: ActiveBinding? = null

    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        val args = createServiceArgs()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                ServiceBootLogger.event("SHIZUKU_ON_CONNECTED", "name=$name binderNull=${binder == null}")
                val binding = activeBinding
                if (binding?.connection !== this) {
                    Timber.w("Ignoring stale Shizuku connection: %s", name)
                    return
                }
                if (binder == null) {
                    callbacks.onError(
                        backend,
                        IllegalStateException("RemoteService binder is null")
                    )
                    return
                }
                Timber.i("RemoteService connected by Shizuku: %s", name)
                callbacks.onConnected(backend, binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ServiceBootLogger.event("SHIZUKU_ON_DISCONNECTED", "name=$name")
                if (activeBinding?.connection !== this) {
                    return
                }
                Timber.i("RemoteService disconnected by Shizuku: %s", name)
                callbacks.onDisconnected(backend)
            }
        }

        val binding = ActiveBinding(args, connection)
        activeBinding = binding

        runCatching {
            ServiceBootLogger.event("SHIZUKU_BIND_CALL", "version=${serviceVersion.get()} tag=$serviceTag")
            Timber.i("Binding remote service via Shizuku: %s", args)
            Shizuku.bindUserService(args, connection)
        }.onFailure { throwable ->
            if (activeBinding == binding) {
                activeBinding = null
            }
            ServiceBootLogger.event("SHIZUKU_BIND_THROW", "${throwable.javaClass.simpleName}: ${throwable.message}")
            Timber.e(throwable, "bindUserService failed")
            callbacks.onError(backend, throwable)
        }
    }

    override fun disconnect(currentBinder: IBinder?) {
        val binding = activeBinding ?: return
        activeBinding = null
        runCatching {
            Shizuku.unbindUserService(binding.args, binding.connection, true)
        }.onFailure {
            Timber.w(it, "unbindUserService failed")
        }
    }

    private fun createServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, RemoteServiceImpl::class.java.name)
        ).apply {
            processNameSuffix("service")
            daemon(false)
            tag(serviceTag)
            version(serviceVersion.incrementAndGet())
            debuggable(BuildConfig.DEBUG)
        }
    }

    private data class ActiveBinding(
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection
    )
}
