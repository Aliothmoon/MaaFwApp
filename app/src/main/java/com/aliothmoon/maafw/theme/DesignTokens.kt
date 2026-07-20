package com.aliothmoon.maafw.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object MaaDesignTokens {

    object Spacing {
        val xxs: Dp = 2.dp
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 20.dp
    }

    object CornerRadius {
        val card: Dp = 12.dp
        val button: Dp = 10.dp
        val pill: Dp = 20.dp
        val inner: Dp = 8.dp
    }

    object Separator {
        val thickness: Dp = 0.5.dp
    }

    object Card {
        /** 卡片类容器统一轻投影；chip/面板/按钮保持平面（docs/design-system.md §4） */
        val elevation: Dp = 2.dp
        val innerPadding: Dp = 16.dp
    }

    object Sheet {
        /** 全部 modal sheet 统一固定高度：屏幕的 3/5。 */
        const val heightFraction = 0.6f
    }
}
