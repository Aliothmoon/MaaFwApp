package com.aliothmoon.maafw.log

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 特权进程被杀，凶手只记在系统日志里，死者自己读不了
 *
 * 死亡时记下时刻与 pid，下次连上新进程时让它翻那段日志；落盘是因为 App 进程未必活到那时
 */
internal class PrivilegedDeathForensics(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    data class Pending(val diedAtMs: Long, val pid: Int) {
        val sinceMs: Long get() = diedAtMs - LOOK_BACK_MS
        val untilMs: Long get() = diedAtMs + LOOK_AHEAD_MS
    }

    interface Store {
        fun load(): Pending?
        fun save(pending: Pending)
        fun clear()
    }

    fun remember(pending: Pending) = store.save(pending)

    /** 同一个 pid 说明它没死（进程被替换也会报死亡）；隔太久系统日志早被冲掉 */
    @Synchronized
    fun take(connectedPid: Int?): Pending? {
        val pending = store.load() ?: return null
        if (connectedPid == null) return null
        store.clear()
        if (pending.pid == connectedPid) return null
        if (clock() - pending.diedAtMs > MAX_AGE_MS) return null
        return pending
    }

    class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): Pending? = prefs.getString(KEY_PENDING, null)?.let {
            runCatching { json.decodeFromString(Pending.serializer(), it) }.getOrNull()
        }

        // 同步落盘：记下死讯后 App 自己也可能马上被收走
        override fun save(pending: Pending) = prefs.edit(commit = true) {
            putString(KEY_PENDING, json.encodeToString(Pending.serializer(), pending))
        }

        override fun clear() = prefs.edit { remove(KEY_PENDING) }
    }

    companion object {
        /** 往回多看一分钟：内存吃紧、冻结这类前兆也在里面 */
        const val LOOK_BACK_MS = 60_000L
        const val LOOK_AHEAD_MS = 15_000L
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        private const val PREFS_NAME = "privileged_death_forensics"
        private const val KEY_PENDING = "pending_death"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
