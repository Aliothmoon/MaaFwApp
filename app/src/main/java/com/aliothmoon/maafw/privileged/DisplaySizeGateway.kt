package com.aliothmoon.maafw.privileged

import android.content.Context
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.ScreenSize
import kotlinx.coroutines.withContext
import com.aliothmoon.maafw.MaaDispatchers
import timber.log.Timber

/**
 * 主屏分辨率的改与撤，以及「当前比例合不合前台模式」的判定
 *
 * 抽接口只为可测：单测里换成记录调用的替身，不去碰 binder 与 WindowManager。
 * 生产实现是 [DisplaySizeController]，由 Koin 显式注入，不做默认参数
 *
 * 与后台虚拟屏的 `ResolutionPreference` 无关——那边是自己建的屏，尺寸由外壳指定；
 * 这里动的是物理主屏，改完整个系统的 UI 都会重排
 */
interface DisplaySizeGateway {

    /** 把主屏改成物理尺寸内能放下的最大 16:9 */
    suspend fun applyFit16x9(): DisplaySizeResult

    /** 撤回出厂分辨率 */
    suspend fun reset(): DisplaySizeResult

    /** 当前生效的主屏比例是否满足前台模式 */
    fun isAspectSupported(): Boolean
}

sealed interface DisplaySizeResult {
    data class Applied(val width: Int, val height: Int) : DisplaySizeResult
    data object Cleared : DisplaySizeResult

    /** 特权进程没连上；文案由 UI 挑，这里只给分类 */
    data object ServiceUnavailable : DisplaySizeResult
    data class Failed(val reason: UiText) : DisplaySizeResult
}

class DisplaySizeController(
    private val context: Context,
    private val servicePort: PrivilegedServicePort,
) : DisplaySizeGateway {

    override suspend fun applyFit16x9(): DisplaySizeResult {
        // 按物理尺寸算而不是当前尺寸：拿改过的值再算一次，连点两下会一路缩下去
        val (physicalWidth, physicalHeight) = ScreenSize.physical(context)
        val target = ScreenSize.fit16x9(physicalWidth, physicalHeight)
            ?: return DisplaySizeResult.Failed(
                uiTextOf(
                    R.string.foreground_resolution_too_small,
                    uiTextFormatted("${physicalWidth}x$physicalHeight"),
                ),
            )
        val (width, height) = target
        return call("setForcedDisplaySize") { it.setForcedDisplaySize(width, height) }
            ?.let { ok ->
                if (ok) DisplaySizeResult.Applied(width, height)
                else DisplaySizeResult.Failed(uiTextOf(R.string.foreground_resolution_apply_failed))
            }
            ?: DisplaySizeResult.ServiceUnavailable
    }

    override suspend fun reset(): DisplaySizeResult =
        call("clearForcedDisplaySize") { it.clearForcedDisplaySize() }
            ?.let { ok ->
                if (ok) DisplaySizeResult.Cleared
                else DisplaySizeResult.Failed(uiTextOf(R.string.foreground_resolution_reset_failed))
            }
            ?: DisplaySizeResult.ServiceUnavailable

    override fun isAspectSupported(): Boolean {
        val (width, height) = ScreenSize.current(context)
        return ScreenSize.isAspect16x9(width, height).also {
            if (!it) Timber.w("primary display %dx%d is not 16:9", width, height)
        }
    }

    /**
     * 走 `useService` 而不是 `serviceOrNull`：这两个动作是用户主动点的，没连上时
     * 顺带发起授权与重绑正是他要的。返回 null = 服务面拿不到
     */
    private suspend fun <R> call(name: String, action: (com.aliothmoon.maafw.RemoteService) -> R): R? =
        withContext(MaaDispatchers.IO) {
            runCatching { servicePort.useService { action(it) } }
                .onFailure { Timber.w(it, "%s failed", name) }
                .getOrNull()
        }
}
