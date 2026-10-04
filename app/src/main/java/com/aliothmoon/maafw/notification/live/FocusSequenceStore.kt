package com.aliothmoon.maafw.notification.live

import android.content.Context
import androidx.core.content.edit

/** 岛的更新序号必须单调递增，否则系统丢弃这次更新 */
internal object FocusSequence {
    fun next(last: Long, nowMs: Long): Long = maxOf(last + 1L, nowMs)
}

/**
 * 岛更新序号，跟随墙上时钟（移植自 MaaMeow）
 *
 * 进度 1 秒一刷不逐次落盘，只为兜住时钟回拨才每 30 秒持久化一次
 */
class FocusSequenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = mutableMapOf<Int, Long>()
    private val persisted = mutableMapOf<Int, Long>()

    @Synchronized
    fun next(notifyId: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val key = "seq_$notifyId"
        val last = cache[notifyId] ?: prefs.getLong(key, 0L)
        val seq = FocusSequence.next(last, nowMs)
        cache[notifyId] = seq
        val lastWritten = persisted[notifyId] ?: prefs.getLong(key, 0L)
        if (seq - lastWritten >= PERSIST_STEP_MS) {
            persisted[notifyId] = seq
            prefs.edit { putLong(key, seq) }
        }
        return seq
    }

    private companion object {
        const val PREFS_NAME = "live_focus_seq"
        const val PERSIST_STEP_MS = 30_000L
    }
}
