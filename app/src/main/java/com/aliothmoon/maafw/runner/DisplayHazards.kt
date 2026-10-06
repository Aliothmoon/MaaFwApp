package com.aliothmoon.maafw.runner

import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.util.EyeProtectionDetector
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 会让识别出错的系统显示设置（对齐 MaaMeow 的启动前检查） */
data class DisplayHazards(
    /** 荣耀「智能分辨率」：降了应用的渲染分辨率，后台虚拟屏上识别会出错 */
    val smartResolution: Boolean = false,
    /** 护眼 / 夜光模式命中的设置项；null = 没开。偏色会影响图像识别 */
    val eyeProtectionSource: String? = null,
)

/** 现读，不缓存：两项用户随时会在系统设置里改 */
fun interface DisplayHazardProbe {
    suspend fun read(): DisplayHazards
}

/**
 * 护眼在 app 里读，智能分辨率问特权进程：前者是各厂商自定义的键，app 读得到；
 * 后者对齐 MaaMeow 走 shell 身份读
 *
 * 只用已有连接，不发起绑定：检查必须零副作用（见 [RunPrecheck]）。没连上就当没开——
 * 手动开跑时特权进程基本都连着，真没连上 Runner 自己会报
 */
class SystemDisplayHazardProbe(
    private val context: Context,
    private val servicePort: PrivilegedServicePort,
) : DisplayHazardProbe {

    override suspend fun read(): DisplayHazards = withContext(MaaDispatchers.IO) {
        val smartResolution = try {
            servicePort.serviceOrNull()?.isSmartResolutionEnabled() == true
        } catch (e: Exception) {
            Timber.w(e, "isSmartResolutionEnabled failed")
            false
        }
        DisplayHazards(smartResolution, EyeProtectionDetector.detect(context))
    }
}

/**
 * 开跑前问一句：先智能分辨率（只坑后台模式）、再护眼
 *
 * 两项都是提醒：定时与悬浮窗没人能点头，照常开跑，由 [DisplayHazardNoticeHook] 记进运行日志
 */
class DisplayHazardPrecheck(private val probe: DisplayHazardProbe) : RunPrecheck {

    override suspend fun evaluate(ctx: RunContext): Verdict {
        val askSmartResolution = ctx.runMode == RunMode.BACKGROUND && SMART_RESOLUTION !in ctx.acknowledged
        val askEyeProtection = EYE_PROTECTION !in ctx.acknowledged
        if (!askSmartResolution && !askEyeProtection) return Verdict.Pass

        val hazards = probe.read()
        return when {
            askSmartResolution && hazards.smartResolution -> Verdict.NeedsConfirmation(
                SMART_RESOLUTION,
                uiTextOf(R.string.precheck_smart_resolution_enabled),
                advisory = true,
            )

            askEyeProtection && hazards.eyeProtectionSource != null -> Verdict.NeedsConfirmation(
                EYE_PROTECTION,
                uiTextOf(R.string.precheck_eye_protection_enabled),
                advisory = true,
            )

            else -> Verdict.Pass
        }
    }

    companion object {
        val SMART_RESOLUTION = ConfirmToken("smart-resolution")
        val EYE_PROTECTION = ConfirmToken("eye-protection")
    }
}

/**
 * 开跑后把仍开着的显示设置记进本轮运行日志
 *
 * 用户点过「仍然启动」也照记：识别出错时翻日志能直接看到原因。
 * 挂 [Anchor.AfterAccepted]：投递之前本轮的日志还没开
 */
class DisplayHazardNoticeHook(
    private val probe: DisplayHazardProbe,
    private val journal: RunJournal,
) : RunEnvHook {

    override val id: String = "display-hazard-notice"
    override val anchor: Anchor = Anchor.AfterAccepted
    override val order: Int = HookOrder.DISPLAY_HAZARD_NOTICE

    /** 报个信而已 */
    override val gating: Boolean = false

    override suspend fun engage(ctx: RunContext): EngageResult {
        val hazards = probe.read()
        val smartResolution = hazards.smartResolution && ctx.runMode == RunMode.BACKGROUND
        if (!smartResolution && hazards.eyeProtectionSource == null) return EngageResult.Skipped()

        if (smartResolution) {
            journal.warn(ctx.executionId, uiTextOf(R.string.run_log_smart_resolution_enabled))
        }
        hazards.eyeProtectionSource?.let { source ->
            journal.warn(
                ctx.executionId,
                uiTextOf(R.string.run_log_eye_protection_enabled, uiTextFormatted(source)),
            )
        }
        // 记了就算挂上，触发日志里能看出这轮报过
        return EngageResult.Engaged(Release { })
    }
}
