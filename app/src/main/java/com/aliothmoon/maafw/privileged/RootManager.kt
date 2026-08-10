package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.RemoteBackend
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object RootManager : RemoteAccessPermissionBackend {

    override val backend = RemoteBackend.ROOT

    private val listeners = CopyOnWriteArraySet<RemoteAccessStateListener>()

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        @Suppress("DEPRECATION")
        Shell.setDefaultBuilder(
            Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
        )
    }

    fun checkPermissionGranted(): Boolean = isGranted()

    override fun isGranted(): Boolean {
        return runCatching {
            Shell.isAppGrantedRoot() == true || Shell.getCachedShell()?.isRoot == true
        }.getOrDefault(false)
    }

    fun isRootAvailable(): Boolean = isAvailable()

    override fun isAvailable(): Boolean {
        if (isGranted()) return true
        val exec = System.getenv("PATH")?.split(":") ?: return false
        for (path in exec) {
            val su = File(path, "su")
            if (su.canExecute()) {
                return true
            }
        }
        return false
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        val granted = runCatching {
            Shell.getShell().isRoot
        }.onFailure {
            Timber.e(it, "request root permission failed")
        }.getOrDefault(false)
        notifyStateChanged()
        granted
    }

    suspend fun <T> requireRootPermissionGranted(action: suspend () -> T): T {
        if (!requestPermission()) {
            throw IllegalStateException("request root permission failed")
        }
        return action()
    }

    override fun addStateListener(listener: RemoteAccessStateListener) {
        listeners += listener
    }

    override fun removeStateListener(listener: RemoteAccessStateListener) {
        listeners -= listener
    }


    private fun notifyStateChanged() {
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(backend) }
                .onFailure { Timber.w(it, "notifyStateChanged failed") }
        }
    }
}
