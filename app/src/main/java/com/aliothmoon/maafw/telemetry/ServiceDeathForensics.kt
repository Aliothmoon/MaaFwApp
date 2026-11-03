package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.util.JsonUtils
import kotlinx.serialization.Serializable

/**
 * 提权进程被杀时，死亡事件先发；新进程连上后再补读系统日志并挂回原事件
 *
 * App 进程未必活到新服务连上，所以事件 ID、死亡时刻和 PID 要同步落盘。
 */
internal class ServiceDeathForensics(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Pending(
        val eventId: String,
        val runId: String?,
        val backend: String?,
        val diedAtMs: Long,
        val pid: Int,
    ) {
        val sinceMs: Long get() = diedAtMs - LOOK_BACK_MS
        val untilMs: Long get() = diedAtMs + LOOK_AHEAD_MS
    }

    interface Store {
        fun load(): Pending?
        fun save(pending: Pending)
        fun clear()
    }

    /** 只留最近一次，连续死几次多半仍是同一个原因 */
    @Synchronized
    fun remember(pending: Pending) {
        val current = store.load()
        if (current == null || pending.diedAtMs >= current.diedAtMs) {
            store.save(pending)
        }
    }

    /**
     * 预读但不消费：上传成功后才能 [complete]
     *
     * 同一个 PID 说明进程没死，只是 binder 被替换；隔太久则系统日志多半已滚掉。
     * 这两种记录已经失去补查价值，可以立即丢弃。
     */
    @Synchronized
    fun peek(connectedPid: Int?): Pending? {
        if (connectedPid == null) return null
        val pending = store.load() ?: return null
        if (pending.pid == connectedPid) {
            store.clear()
            return null
        }
        if (clock() - pending.diedAtMs > MAX_AGE_MS) {
            store.clear()
            return null
        }
        return pending
    }

    /** 只清除刚才预读的那条，不能让更晚发生的死亡记录一起消失 */
    @Synchronized
    fun complete(pending: Pending) {
        if (store.load() == pending) {
            store.clear()
        }
    }

    class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): Pending? = prefs.getString(KEY_PENDING, null)?.let {
            runCatching { JsonUtils.common.decodeFromString<Pending>(it) }.getOrNull()
        }

        override fun save(pending: Pending) {
            prefs.edit()
                .putString(KEY_PENDING, JsonUtils.common.encodeToString(pending))
                .commit()
        }

        override fun clear() {
            prefs.edit().remove(KEY_PENDING).commit()
        }
    }

    private companion object {
        /** 往回多看一分钟：内存吃紧、冻结这类前兆也在里面 */
        const val LOOK_BACK_MS = 60_000L
        const val LOOK_AHEAD_MS = 15_000L
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        const val PREFS_NAME = "telemetry_forensics"
        const val KEY_PENDING = "pending_service_death"
    }
}
