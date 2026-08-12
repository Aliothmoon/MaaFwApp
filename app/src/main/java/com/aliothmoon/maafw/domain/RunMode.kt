package com.aliothmoon.maafw.domain

import com.aliothmoon.maafw.constant.DisplayMode

enum class RunMode(val displayMode: Int) {
    FOREGROUND(DisplayMode.PRIMARY),
    BACKGROUND(DisplayMode.BACKGROUND),
}
