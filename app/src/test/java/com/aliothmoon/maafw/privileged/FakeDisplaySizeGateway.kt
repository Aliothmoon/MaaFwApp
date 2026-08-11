package com.aliothmoon.maafw.privileged

/**
 * 主屏分辨率的测试替身
 *
 * [aspectSupported] 默认 true：绝大多数用例不关心前台模式那道闸，
 * 让它默认放行，要验拦截的用例自己置 false
 */
class FakeDisplaySizeGateway(
    var aspectSupported: Boolean = true,
    var applyResult: DisplaySizeResult = DisplaySizeResult.Applied(1920, 1080),
    var resetResult: DisplaySizeResult = DisplaySizeResult.Cleared,
) : DisplaySizeGateway {

    var applyCount: Int = 0
        private set
    var resetCount: Int = 0
        private set

    override suspend fun applyFit16x9(): DisplaySizeResult {
        applyCount++
        return applyResult
    }

    override suspend fun reset(): DisplaySizeResult {
        resetCount++
        return resetResult
    }

    override fun isAspectSupported(): Boolean = aspectSupported
}
