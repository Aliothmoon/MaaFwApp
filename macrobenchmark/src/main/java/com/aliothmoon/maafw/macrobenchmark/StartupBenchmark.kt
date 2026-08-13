package com.aliothmoon.maafw.macrobenchmark

import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 跑的是 `.benchmark` 后缀那个包，不是装机用的那个——[coldStartupAfterClear] 会 `pm clear`
 *
 * 每轮落一份 Perfetto trace 到 `build/outputs/connected_android_test_additional_output/`
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * 装完第一次启动：解包与首次加载都在首帧之后异步跑，[StartupTimingMetric] 够不着，
     * 各自单列成 trace section。段名与产地对不上时这里出 0 而不是报错
     *
     * 别给 `.benchmark` 这个包授 Shizuku：授了它一连上特权进程就自动启用自己的无障碍服务，
     * 系统随即常驻拉起它的进程，`must not be running prior to cold start` 就再也过不去
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun coldStartupAfterClear() = rule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric(PI_UNPACK_SECTION, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(PROJECT_LOAD_SECTION, TraceSectionMetric.Mode.Sum),
        ),
        iterations = CLEAR_ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            invalidatePi()
            // COLD 的那次 killProcess 排在 setupBlock 之前
            killProcess()
        },
    ) {
        startActivityAndWait()
        // 测量块得活到那两段结束；等的这段不进任何指标
        Thread.sleep(SETTLE_MILLIS)
    }

    /**
     * 删掉解包标记就够了：PiInstaller 比对不上 versionCode 就整体重解
     *
     * 不用 `pm clear`——MIUI 会在清完之后立刻把进程拉回来，macrobenchmark 的 COLD
     * 断言当场就炸；而且那还会连 DataStore 一起洗掉，测的就不是同一件事了
     */
    private fun invalidatePi() {
        val dir = "/sdcard/Android/data/${BuildConfig.TARGET_PACKAGE}/files"
        shell("rm -f $dir/pi.version")
        shell("rm -rf $dir/pi")
    }

    /** 读到 EOF 才算完：executeShellCommand 是异步的，只 close 会撞上还没做完的那一步 */
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
    }

    private companion object {
        const val ITERATIONS = 10

        /** 每轮重解一遍几十 MB */
        const val CLEAR_ITERATIONS = 5

        /** 宁可多等，等短了段就被截断 */
        const val SETTLE_MILLIS = 30_000L

        const val PI_UNPACK_SECTION = "MaaPiUnpack"
        const val PROJECT_LOAD_SECTION = "MaaProjectLoad"
    }
}
