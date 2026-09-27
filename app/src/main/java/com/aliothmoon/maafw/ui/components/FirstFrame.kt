package com.aliothmoon.maafw.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * 首帧为 false，下一帧起为 true
 *
 * 给页面拆帧用：首帧只挂容器（比如 LazyColumn），重内容晚一帧再组合。Compose 在手指按下那一刻
 * 就定下整次手势归谁，容器若和一屏重内容挤在同一帧，这一帧没画完之前按下的手指绑不到列表上，
 * 刚进页面就拖会整次拖不动
 */
@Composable
fun rememberAfterFirstFrame(): Boolean {
    var ready by remember { mutableStateOf(false) }
    if (!ready) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            ready = true
        }
    }
    return ready
}
