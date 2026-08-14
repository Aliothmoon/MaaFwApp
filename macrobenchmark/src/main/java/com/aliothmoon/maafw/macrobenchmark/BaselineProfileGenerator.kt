package com.aliothmoon.maafw.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 产出 `app/src/<variant>/generated/baselineProfiles/`，由 AGP 打进 APK
 *
 * 跑 `./gradlew :app:generateBaselineProfile`。API 28~32 上采集要 root（本机走 Magisk 的 su），
 * 33 起不需要
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** 进程创建到首帧那条链，单列进 startup profile */
    @Test
    fun startup() = rule.collect(
        packageName = BuildConfig.TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * 四个主 tab 各走一遍：任务页那棵树是实测最重的一处，不覆盖它等于把收益让掉一半
     *
     * 不进 startup profile——它是首帧之后的事，混进去只会把启动那份撑大
     */
    @Test
    fun mainTabs() = rule.collect(
        packageName = BuildConfig.TARGET_PACKAGE,
        includeInStartupProfile = false,
    ) {
        startActivityAndWait()
        dismissShizukuGuidance()
        repeat(TAB_COUNT) { index -> tapTab(index) }
        tapTab(0)
    }

    /**
     * 采集常跑在没装 Shizuku 的模拟器上，那里首启会弹引导，盖住底栏让 tab 全点空
     *
     * 只在真有它时才按返回——没有弹窗时按下去会直接把 Activity 关掉。
     * 认 "Shizuku" 这个专名而不是提示文案：那句话中英两份资源都有
     */
    private fun MacrobenchmarkScope.dismissShizukuGuidance() {
        if (!device.hasObject(By.textContains("Shizuku"))) return
        device.pressBack()
        device.waitForIdle()
    }

    /** 底栏是自建的，没有资源 id 可选，只能按屏幕比例点；四个 tab 均分宽度 */
    private fun MacrobenchmarkScope.tapTab(index: Int) {
        val width = device.displayWidth
        val height = device.displayHeight
        val x = (width * (2 * index + 1) / (2 * TAB_COUNT))
        device.click(x, (height * BOTTOM_BAR_Y).toInt())
        device.waitForIdle()
        Thread.sleep(TAB_SETTLE_MILLIS)
    }

    private companion object {
        const val TAB_COUNT = 4

        /** 底栏图标大致落在这个高度，导航栏在它之下 */
        const val BOTTOM_BAR_Y = 0.955

        /** 够切页动画与那一屏的首次组合跑完 */
        const val TAB_SETTLE_MILLIS = 1_500L
    }
}
