package com.aliothmoon.maafw.runner

import android.content.Context
import android.content.SharedPreferences
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.remote.ProcessExitRecord
import com.aliothmoon.maafw.remote.ProcessExitWire
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessExitRecorderTest {

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    @Test
    fun `service reconnect backfills the last game package`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = MemoryPreferences()
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSharedPreferences(any(), any()) } returns prefs
        }
        val first = record(timestamp = 100, pid = 10)
        val second = record(timestamp = 200, pid = 11)
        val service = FakePrivilegedService().apply {
            processExitRecordsJson = ProcessExitWire.encode(listOf(first))
        }
        val port = FakePrivilegedServicePort(service)

        ProcessExitRecorder(context, port).recordGame(" com.example.game ")

        assertEquals("com.example.game", service.lastProcessExitQuery?.first)
        service.processExitRecordsJson = ProcessExitWire.encode(listOf(first, second))

        // 新实例模拟 App 被杀后重启；包名与水位都应从磁盘恢复
        val restarted = ProcessExitRecorder(context, port)
        val connection = backgroundScope.launch {
            restarted.recordKnownGameOnServiceConnect()
        }
        runCurrent()

        assertEquals(
            Triple("com.example.game", first.timestamp, 16),
            service.lastProcessExitQuery,
        )
        connection.cancel()
    }

    @Test
    fun `remembering a game package does not query process exits`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSharedPreferences(any(), any()) } returns MemoryPreferences()
        }
        val service = FakePrivilegedService()
        val port = FakePrivilegedServicePort(service)
        val recorder = ProcessExitRecorder(context, port)

        recorder.rememberGamePackage(" com.example.game ")

        assertEquals(null, service.lastProcessExitQuery)

        recorder.recordKnownGame()
        assertEquals("com.example.game", service.lastProcessExitQuery?.first)
    }

    private fun record(timestamp: Long, pid: Int) = ProcessExitRecord(
        timestamp = timestamp,
        pid = pid,
        processName = "com.example.game",
        reason = 9,
        reasonName = "KILLED",
        detail = 0,
        detailName = "",
        status = 0,
        importance = 100,
        pssKb = 1024,
        rssKb = 2048,
        description = null,
    )

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { updates[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { updates[key] = values }

            override fun putInt(key: String, value: Int) = apply { updates[key] = value }

            override fun putLong(key: String, value: Long) = apply { updates[key] = value }

            override fun putFloat(key: String, value: Float) = apply { updates[key] = value }

            override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }

            override fun remove(key: String) = apply { removals += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(updates)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
