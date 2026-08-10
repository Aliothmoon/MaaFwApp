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

        /** 分段按钮相邻的那一侧；比 [inner] 更收，两段才看得出是一体的 */
        val segment: Dp = 4.dp
    }

    object Separator {
        val thickness: Dp = 0.5.dp
    }

    /** 描边宽度；分隔线用 [Separator] */
    object Border {
        /** 选中态卡片 */
        val selected: Dp = 1.dp

        /** 单选标记的圈 */
        val marker: Dp = 2.dp
    }

    /**
     * 图标绘制尺寸；不要在 `Modifier.size(N.dp)` 上拍裸数
     * 新场景对不上现有档时，先在这里加档并写清用途
     */
    object IconSize {
        /** badge 与选中标记里的图标 */
        val xs: Dp = 12.dp

        /** 行内装饰图标，与 bodyLarge / labelLarge 并排 */
        val sm: Dp = 16.dp

        /** IconButton 与按钮前置图标的标准档 */
        val md: Dp = 20.dp

        /** 卡片内的占位插画 */
        val lg: Dp = 32.dp

        /** 整屏空状态与错误态插画 */
        val xl: Dp = 44.dp

        /** 分组色点：chip 内 */
        val dotSm: Dp = 6.dp

        /** 分组色点：列表分组头 */
        val dotMd: Dp = 8.dp
    }

    /** 图标外面那层圆或圆角方容器；内部图标仍取 [IconSize] */
    object IconContainer {
        /** 单选标记的圈 */
        val xs: Dp = 20.dp

        /** 行尾的展开/收起圆钮 */
        val sm: Dp = 28.dp

        /** 行首的主图标底 */
        val md: Dp = 32.dp
    }

    object Card {
        /** 卡片类容器统一轻投影；chip/面板/按钮保持平面（docs/design-system.md §4） */
        val elevation: Dp = 2.dp

        /** 拖拽中抬起的高度，让被拖的行压过邻居 */
        val dragElevation: Dp = 6.dp
        val innerPadding: Dp = 16.dp
    }

    object Alpha {
        /** 不可交互的卡片整体压暗 */
        const val disabled = 0.5f

        /** 锁定时的文字与图标 */
        const val disabledContent = 0.4f

        /** 次要说明文字 */
        const val secondary = 0.7f
    }

    object Sheet {
        /** 全部 modal sheet 统一固定高度：屏幕的 3/5 */
        const val heightFraction = 0.6f
    }
}
