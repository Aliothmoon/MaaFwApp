package com.aliothmoon.maafw.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 漏触发账本：正常链挂上的触发点、闹钟到达的触发点，换新触发点时据此判漏 */
class ScheduleAlarmManagerTest {

    private val platformAlarms = mockk<AlarmManager>(relaxed = true)
    private val ledger = FakePrefs()
    private val context = mockk<Context> {
        every { getSystemService(Context.ALARM_SERVICE) } returns platformAlarms
        every { getSharedPreferences(any(), any()) } returns ledger
    }
    private lateinit var alarms: ScheduleAlarmManager

    private val hourly = ScheduleStrategy(
        id = "hourly",
        name = "Hourly",
        scheduleType = ScheduleType.INTERVAL,
        startTimeMs = System.currentTimeMillis() - 7_200_000L,
        intervalHours = 1,
    )

    @Before
    fun setUp() {
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 30L * 86_400_000L
        alarms = ScheduleAlarmManager(context)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun missedOnlyAfterGraceAndWhenNotFired() {
        val armed = 1_000_000_000L
        val late = armed + ScheduleAlarmManager.MISSED_GRACE_MS
        assertFalse(ScheduleAlarmManager.isMissed(0L, 0L, late + 1))
        assertFalse(ScheduleAlarmManager.isMissed(armed, 0L, late))
        assertTrue(ScheduleAlarmManager.isMissed(armed, 0L, late + 1))
        assertTrue(ScheduleAlarmManager.isMissed(armed, armed - 1, late + 1))
        assertFalse(ScheduleAlarmManager.isMissed(armed, armed, late + 1))
    }

    @Test
    fun rearmingOverAnUnfiredSlotQueuesItAsMissed() {
        val stale = System.currentTimeMillis() - 3_600_000L
        ledger.data["armed:${hourly.id}"] = stale

        alarms.scheduleNext(hourly)
        assertEquals(
            listOf(ScheduleAlarmManager.MissedTrigger(hourly.id, stale, rebooted = false)),
            alarms.drainMissed(),
        )
        assertTrue(alarms.drainMissed().isEmpty())

        // 之后别的触发点到了：照报
        ledger.data["armed:${hourly.id}"] = stale
        alarms.scheduleNext(hourly)
        alarms.markFired(hourly.id, stale + 3_600_000L)
        assertEquals(1, alarms.drainMissed().size)

        // 同一触发点晚到：不报
        alarms.markFired(hourly.id, stale - 1)
        ledger.data["armed:${hourly.id}"] = stale
        alarms.scheduleNext(hourly)
        alarms.markFired(hourly.id, stale)
        assertTrue(alarms.drainMissed().isEmpty())
    }

    @Test
    fun bootAfterTheTriggerMarksItRebooted() {
        val stale = System.currentTimeMillis() - 3_600_000L
        // 半小时前开机，触发点在那之前
        every { SystemClock.elapsedRealtime() } returns 1_800_000L
        ledger.data["armed:${hourly.id}"] = stale

        alarms.scheduleNext(hourly)

        assertEquals(listOf(ScheduleAlarmManager.MissedTrigger(hourly.id, stale, rebooted = true)), alarms.drainMissed())
    }

    /** 整批重排先撤槽位再排下一环，旧触发点要留到判完漏触发 */
    @Test
    fun rescheduleAllJudgesTheOldSlotBeforeReplacingIt() {
        val stale = System.currentTimeMillis() - 3_600_000L
        ledger.data["armed:${hourly.id}"] = stale

        alarms.rescheduleAll(listOf(hourly))

        assertEquals(1, alarms.drainMissed().size)
        assertTrue((ledger.data["armed:${hourly.id}"] as Long) > System.currentTimeMillis())
    }

    @Test
    fun disablingOrFailingToRescheduleClearsArmedSlot() {
        ledger.data["armed:${hourly.id}"] = 1L
        alarms.cancel(hourly.id)
        assertFalse(ledger.data.containsKey("armed:${hourly.id}"))

        ledger.data["armed:${hourly.id}"] = System.currentTimeMillis() + 3_600_000L
        every { platformAlarms.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        alarms.scheduleNext(hourly)
        assertFalse(ledger.data.containsKey("armed:${hourly.id}"))
        assertTrue(alarms.drainMissed().isEmpty())
    }

    /** 编辑保存：挂着的重试补的是错过的那一次，留着；没有才撤了重排，旧触发点一起撤 */
    @Test
    fun editKeepsAPendingRetryOtherwiseRearms() {
        val now = System.currentTimeMillis()
        ledger.data["retry:${hourly.id}"] = PendingRetry(now - 60_000L, retryCount = 1, triggerMs = now + 60_000L).encode()
        ledger.data["armed:${hourly.id}"] = now - 60_000L

        alarms.replace(hourly)
        assertTrue(ledger.data.containsKey("retry:${hourly.id}"))
        assertEquals(now - 60_000L, ledger.data["armed:${hourly.id}"])

        ledger.data.remove("retry:${hourly.id}")
        alarms.replace(hourly)
        assertTrue((ledger.data["armed:${hourly.id}"] as Long) > now)
        assertTrue(alarms.drainMissed().isEmpty())
    }

    @Test
    fun forgetDropsTheWholeLedgerOfAStrategy() {
        ledger.data["armed:${hourly.id}"] = 1L
        ledger.data["fired:${hourly.id}"] = 1L
        ledger.data["delivered:${hourly.id}"] = 1L

        alarms.forget(hourly.id)

        assertTrue(ledger.data.keys.none { it.endsWith(hourly.id) })
    }

    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in data
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            override fun putString(key: String, value: String?) = apply { ops += { it[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { ops += { it[key] = values } }
            override fun putInt(key: String, value: Int) = apply { ops += { it[key] = value } }
            override fun putLong(key: String, value: Long) = apply { ops += { it[key] = value } }
            override fun putFloat(key: String, value: Float) = apply { ops += { it[key] = value } }
            override fun putBoolean(key: String, value: Boolean) = apply { ops += { it[key] = value } }
            override fun remove(key: String) = apply { ops += { it.remove(key) } }
            override fun clear() = apply { ops += { it.clear() } }
            override fun commit(): Boolean {
                ops.forEach { it(data) }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
