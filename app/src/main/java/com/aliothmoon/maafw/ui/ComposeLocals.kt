package com.aliothmoon.maafw.ui

import androidx.compose.runtime.compositionLocalOf

/** 当前是否处于悬浮窗（overlay）输入场景；ITextField 据此切换 Compose TextField 与 EditText 实现 */
val LocalFloatingWindowContext = compositionLocalOf { false }
