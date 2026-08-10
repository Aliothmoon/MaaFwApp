package com.aliothmoon.maafw.schedule

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
enum class ScheduleType {
    /** 固定星期 + 时刻 */
    FIXED_TIME,

    /** 指定起点 + 固定间隔 */
    INTERVAL,
}

/** ISO 数值：1=周一 … 7=周日；比枚举名短且与 java.time 同构 */
object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DayOfWeek", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: DayOfWeek) = encoder.encodeInt(value.value)

    override fun deserialize(decoder: Decoder): DayOfWeek = DayOfWeek.of(decoder.decodeInt())
}

/** "HH:mm"；秒与纳秒对定时无意义，落盘也更好读 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) =
        encoder.encodeString(value.format(formatter))

    override fun deserialize(decoder: Decoder): LocalTime =
        LocalTime.parse(decoder.decodeString(), formatter)
}

/**
 * 一条定时规则
 *
 * 当前只到「按时把 app 叫醒并记一笔」为止，**不触发任何 MaaFramework 执行**——
 * 接执行时再补目标运行配置的字段，届时盘上老数据要走一次迁移
 */
@Serializable
data class ScheduleStrategy(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val scheduleType: ScheduleType = ScheduleType.FIXED_TIME,
    /** [ScheduleType.FIXED_TIME]：命中的星期 */
    val daysOfWeek: Set<@Serializable(with = DayOfWeekSerializer::class) DayOfWeek> = emptySet(),
    /** [ScheduleType.FIXED_TIME]：每天的触发时刻，已排序 */
    val executionTimes: List<@Serializable(with = LocalTimeSerializer::class) LocalTime> = emptyList(),
    /** [ScheduleType.INTERVAL]：首次触发的绝对时间 */
    val startTimeMs: Long? = null,
    /** [ScheduleType.INTERVAL]：间隔分钟数 */
    val intervalMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val lastResult: TriggerResult? = null,
    val lastResultMessage: String? = null,
)

/** 一次触发的落点；执行未接入前只会出现前两种 */
@Serializable
enum class TriggerResult {
    /** 闹钟到点、服务起来了 */
    TRIGGERED,

    /** 策略被删或数据没读出来 */
    FAILED_VALIDATION,

    /** 前台服务起不来，闹钟链靠 receiver 兜底续上 */
    FAILED_SERVICE_START,
}
