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

/** 一条定时规则 */
@Serializable
data class ScheduleStrategy(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val scheduleType: ScheduleType = ScheduleType.FIXED_TIME,
    /**
     * 要跑哪份运行配置；**null = 跟随当前激活的那份**
     *
     * null 是编辑页可选的一档，不只是老数据的兼容值——盘上没有这个字段的旧规则
     * 反序列化即得 null，语义正好是「跟着我当前用的配置走」，不需要单独的迁移步骤
     */
    val runConfigurationId: String? = null,
    /** [ScheduleType.FIXED_TIME]：命中的星期 */
    val daysOfWeek: Set<@Serializable(with = DayOfWeekSerializer::class) DayOfWeek> = emptySet(),
    /** [ScheduleType.FIXED_TIME]：每天的触发时刻，已排序 */
    val executionTimes: List<@Serializable(with = LocalTimeSerializer::class) LocalTime> = emptyList(),
    /** [ScheduleType.INTERVAL]：首次触发的绝对时间 */
    val startTimeMs: Long? = null,
    /** [ScheduleType.INTERVAL]：间隔天数 */
    val intervalDays: Int? = null,
    /** [ScheduleType.INTERVAL]：间隔小时数 */
    val intervalHours: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val lastResult: TriggerResult? = null,
    val lastResultMessage: String? = null,
)

/** 一次触发的落点 */
@Serializable
enum class TriggerResult {
    /** 闹钟到点、服务起来了，但没发起执行；接执行之前的老记录只有这一种 */
    TRIGGERED,

    /** 执行已被 RunnerPort 受理；跑得怎么样看运行日志，不回灌这里 */
    STARTED,

    /** 服务起来了但没能发起执行，细分见 [TriggerFailureReason] */
    FAILED_START,

    /** 策略被删或数据没读出来 */
    FAILED_VALIDATION,

    /** 前台服务起不来，闹钟链靠 receiver 兜底续上 */
    FAILED_SERVICE_START,
}

/**
 * [TriggerResult.FAILED_START] 的细分
 *
 * 存枚举而不是文案：与 [TriggerLogEntry] 同一条理由，落盘的字符串会把语言冻住。
 * 拦截来自哪道检查存不下（那是 `UiText`），只进 Timber
 */
@Serializable
enum class TriggerFailureReason {
    /** 项目还没加载好或加载失败 */
    PROJECT_NOT_READY,

    /** 规则指定的运行配置已被删除 */
    CONFIGURATION_MISSING,

    NO_EXECUTABLE_TASKS,

    /** RunPlan 编译出错 */
    INVALID_PLAN,

    /** 被某道前置检查拦下；定时触发下「需要确认」也降级到这里 */
    BLOCKED,

    /** RunnerPort 拒绝，多半是已有执行在跑 */
    REJECTED,
}
/** 编辑期字段级校验；空集 = 可保存 */
enum class ScheduleFieldError { NAME, DAYS, TIMES, START, INTERVAL }

val ScheduleStrategy.validationErrors: Set<ScheduleFieldError>
    get() = buildSet {
        if (name.trim().isBlank()) add(ScheduleFieldError.NAME)
        when (scheduleType) {
            ScheduleType.FIXED_TIME -> {
                if (daysOfWeek.isEmpty()) add(ScheduleFieldError.DAYS)
                if (executionTimes.isEmpty()) add(ScheduleFieldError.TIMES)
            }
            ScheduleType.INTERVAL -> {
                if (startTimeMs == null) add(ScheduleFieldError.START)
                if ((intervalDays ?: 0) * 24 + (intervalHours ?: 0) <= 0) add(ScheduleFieldError.INTERVAL)
            }
        }
    }

/** 是否可保存（无字段错误）。UI 门禁保存钮 + VM 兜底都用它 */
val ScheduleStrategy.isValid: Boolean get() = validationErrors.isEmpty()
