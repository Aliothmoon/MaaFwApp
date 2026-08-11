package com.aliothmoon.maafw.project

import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 内存 PI 包；清单由构建期产出，这里直接给
 * [openCount] 用来判「这一轮到底解没解」，解包并发跑，计数要自己上锁
 */
class MapPiPackage(private val files: Map<String, String>) : PiPackage {

    @Volatile
    var openCount = 0
        private set

    override fun manifest(): List<String> = files.keys.sorted()

    @Synchronized
    override fun open(path: String): InputStream {
        val content = files[path] ?: throw FileNotFoundException(path)
        openCount++
        return content.byteInputStream()
    }
}
