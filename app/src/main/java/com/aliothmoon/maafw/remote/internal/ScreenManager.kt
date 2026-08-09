package com.aliothmoon.maafw.remote.internal

import android.view.Display
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import java.io.File

object ScreenManager {
    private const val TAG = "ScreenManager"
    private val _flag = File("/data/local/tmp/maa_screen_flag")

    var flag: Boolean
        get() = runCatching { _flag.exists() }.onFailure {
            Ln.e("$TAG: Failed to check if alive flag file exists: ${it.message}")
            Ln.e(it.stackTraceToString())
        }.getOrDefault(false)
        set(value) {
            runCatching {
                if (value) {
                    _flag.parentFile?.mkdirs()
                    _flag.createNewFile()
                } else {
                    _flag.delete()
                }
            }.onFailure {
                Ln.e("$TAG: Failed to set alive flag file: ${it.message}")
                Ln.e(it.stackTraceToString())
            }
        }

    fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        flag = true
        return ServiceManager.getWindowManager()
            .setForcedDisplaySize(Display.DEFAULT_DISPLAY, width, height)
    }

    fun clearForcedDisplaySize(): Boolean {
        flag = false
        return ServiceManager.getWindowManager().clearForcedDisplaySize(Display.DEFAULT_DISPLAY)
    }

    fun destroy() {
        if (flag) {
            Ln.i("$TAG: Emergency recovering display size...")
            runCatching {
                clearForcedDisplaySize()
            }.onFailure {
                Ln.e("$TAG: Failed to clear forced display size: ${it.message}")
            }
        }
    }
}