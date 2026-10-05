package com.aliothmoon.maafw.runner

import android.os.Build
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.AndroidVersions
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf

class BackgroundModePrecheck(private val sdkInt: Int = Build.VERSION.SDK_INT) : RunPrecheck {

    override suspend fun evaluate(ctx: RunContext): Verdict =
        if (ctx.runMode == RunMode.BACKGROUND && sdkInt < AndroidVersions.API_29_ANDROID_10) {
            Verdict.Block(uiTextOf(R.string.runner_background_unsupported))
        } else {
            Verdict.Pass
        }
}
