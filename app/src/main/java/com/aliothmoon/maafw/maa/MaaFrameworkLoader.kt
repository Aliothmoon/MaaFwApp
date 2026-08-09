package com.aliothmoon.maafw.maa

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

    /**
     * JNA 解压临时文件的落点
     * 默认落 app 私有目录，shell 身份写不进去；这里指到 shell 可写的位置
     */
    private const val JNA_TMPDIR = "/data/local/tmp"

    val library: MaaFrameworkLibrary? by lazy {
        runCatching {
            System.setProperty("jna.tmpdir", JNA_TMPDIR)
            RemoteBootTrace.mark("MAA_LOAD_BEGIN", "jna.tmpdir=$JNA_TMPDIR")
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
