package com.aliothmoon.maafw.log

import android.annotation.SuppressLint
import android.util.Log
import com.aliothmoon.maafw.BuildConfig
import timber.log.Timber

/**
 * 三棵树的分工
 *
 * - [ShortTagDebugTree]  debug 下打全量到 logcat，tag 取类名
 * - [ReleaseTree]        release 下只放 W 以上进 logcat
 * - [FileLogTree]        两种构建都落盘，档位跟设置里的调试模式走
 *
 * 落盘那棵是重点：logcat 环形缓冲装不下一次长跑，而定时触发多半发生在没人看着的时候
 */

/**
 * Timber 默认拿调用处的完整类名当 tag，logcat 会截断
 * 这里剥掉包名与 Kotlin 文件类的 `Kt` 后缀，再按 23 字符上限截
 */
class ShortTagDebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String {
        val simple = element.className.substringAfterLast('.')
        val tag = when {
            // 顶层函数编成 XxxKt，匿名类与 lambda 编成 Xxx$1
            simple.endsWith("Kt") -> simple.dropLast(2)
            simple.contains('$') -> simple.substringBefore('$')
            else -> simple
        }
        return tag.take(MAX_TAG_LENGTH)
    }

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}

/** release 下压掉 D/I/V：正常运行的日志量没有价值，还会拖慢热路径 */
class ReleaseTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    @SuppressLint("LogNotTimber")
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        when (priority) {
            Log.WARN -> Log.w(tag, message, t)
            Log.ERROR -> Log.e(tag, message, t)
            Log.ASSERT -> Log.wtf(tag, message, t)
        }
    }
}

/**
 * 只负责转交给 [AppLogWriter]，节流与轮转都在那边
 *
 * 继承 DebugTree 而不是 Tree：tag 的栈推导在 `DebugTree.getTag()` 里，
 * 裸 Tree 拿到的 tag 恒为 null，落盘出来每行都是「-」。覆盖 log 之后不会再写 logcat
 */
class FileLogTree(
    private val writer: AppLogWriter,
    /**
     * 跟设置里的调试模式，不跟构建类型：这份落盘件在界面上叫「错误日志」，
     * 关着就该只有 W 以上，否则用户翻开满屏是 D/I 流水
     */
    private val verbose: () -> Boolean,
) : Timber.DebugTree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        verbose() || priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        writer.submit(priority, tag, message, t)
    }
}

class LogTreeHolder(
    private val writer: AppLogWriter,
    private val verbose: () -> Boolean,
) {

    fun setup() {
        Timber.uprootAll()
        Timber.plant(
            *arrayOf(
                if (BuildConfig.DEBUG) ShortTagDebugTree() else ReleaseTree(),
                FileLogTree(writer, verbose)
            )
        )
    }
}
