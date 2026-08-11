package com.aliothmoon.maafw.maa

import com.aliothmoon.maafw.constant.ShellDirs
import com.aliothmoon.maafw.remote.RemoteBootTrace
import com.aliothmoon.maafw.third.Ln
import com.sun.jna.Native

/**
 * 特权进程里加载 libMaaFramework.so
 *
 * 只在特权进程内使用：控制单元会 dlopen 到本进程，而截屏与输入注入要 shell 身份
 * 加载失败不抛，返回 null 由调用方转成可读失败
 */
object MaaFrameworkLoader {

    private const val LIBRARY_NAME = "MaaFramework"

    val library: MaaFrameworkLibrary? by lazy {
        runCatching {
            System.setProperty("jna.tmpdir", ShellDirs.JNA_TMPDIR)
            RemoteBootTrace.mark("MAA_LOAD_BEGIN", "jna.tmpdir=${ShellDirs.JNA_TMPDIR}")
            Native.load(LIBRARY_NAME, MaaFrameworkLibrary::class.java).also {
                RemoteBootTrace.mark("MAA_LOAD_OK", it.MaaVersion().orEmpty())
                Ln.i("MaaFramework loaded: ${it.MaaVersion()}")
            }
        }.onFailure {
            RemoteBootTrace.mark("MAA_LOAD_FAIL", "${it.javaClass.simpleName}: ${it.message}")
            Ln.e("Failed to load $LIBRARY_NAME: ${it.message}")
            Ln.e(it.stackTraceToString())
        }.getOrNull()
    }
}
