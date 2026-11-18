package com.aliothmoon.maafw.remote.internal

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 写满一段挪成 `<name>.1.<ext>` 再从头写，只留最新两段；现场在尾部，到上限不能停写 */
internal class RollingLogFile(
    private val file: File,
    private val maxSegmentBytes: Long,
) : Closeable {

    private val previous = File(file.parentFile, "${file.nameWithoutExtension}.1.${file.extension}")
    private var written = file.length()
    private var out: OutputStream = open(append = true)

    fun writeLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (written > 0 && written + bytes.size + 1 > maxSegmentBytes) roll()
        out.write(bytes)
        out.write('\n'.code)
        written += bytes.size + 1
    }

    fun flush() = out.flush()

    override fun close() = out.close()

    private fun roll() {
        out.close()
        previous.delete()
        file.renameTo(previous)
        out = open(append = false)
        written = 0
    }

    private fun open(append: Boolean) = BufferedOutputStream(FileOutputStream(file, append))
}
