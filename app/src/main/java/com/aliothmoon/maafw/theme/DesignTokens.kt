package com.aliothmoon.maafw.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 与 [ThemeStyle] 无关的静态尺寸：间距、图标、描边、透明度
 * 圆角与卡片 elevation 随风格变，见 [MaaStyleTokens] / [MaaTheme.style]
 */
object MaaDesignTokens {

    object Spacing {
        val xxs: Dp = 2.dp

        /** 介于 [xxs] 与 [xs]：紧凑行竖直内边距等「再收一档又不贴死」 */
        val xxsLg: Dp = 3.dp

        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 20.dp
    }

    /**
     * DEFAULT 风格的圆角档；Composable 内优先 [MaaTheme.style].radii，
     * 仅非组合上下文或默认参数回落时用这里
     */
    object CornerRadius {
        val card: Dp = DefaultStyleTokens.radii.card
        val button: Dp = DefaultStyleTokens.radii.button
        val pill: Dp = DefaultStyleTokens.radii.pill
        val inner: Dp = DefaultStyleTokens.radii.inner
        val segment: Dp = DefaultStyleTokens.radii.segment
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

    /**
     * DEFAULT 卡片度量；elevation 随风格变时用 [MaaTheme.style]
     * innerPadding / dragElevation 两风格共用
     */
    object Card {
        val elevation: Dp = DefaultStyleTokens.cardElevation
        val dragElevation: Dp = DefaultStyleTokens.dragElevation
        val innerPadding: Dp = DefaultStyleTokens.cardInnerPadding
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
        /** 全部 modal sheet 统一固定高度：屏幕的 3/4 */
        const val heightFraction = 0.75f
    }
}

/**
 * 随 [ThemeStyle] 变化的圆角档；间距/触控节奏不进此结构
 * Semi 档位对应官方 token：small→button/inner，medium→card，large→large，full→pill
 */
@Immutable
data class MaaRadii(
    val card: Dp,
    val button: Dp,
    val pill: Dp,
    val inner: Dp,
    /** 分段按钮相邻侧；比 [inner] 更收 */
    val segment: Dp,
    /** 大容器（sheet / 大面板）；Semi 的 border-radius-large */
    val large: Dp = card,
)

/**
 * 随 [ThemeStyle] 变化的表面度量
 *
 * - DEFAULT：略圆 + 卡片 1dp 轻投影
 * - SEMI_DESIGN：圆角对齐 Semi Design token（small 3 / medium 6 / large 12 / full 胶囊），
 *   卡片 elevation 0，靠描边分层
 * 拖拽抬升与内边距两风格共用，不做成第二套密度
 */
@Immutable
data class MaaStyleTokens(
    val radii: MaaRadii,
    val cardElevation: Dp,
    val dragElevation: Dp = 6.dp,
    val cardInnerPadding: Dp = 16.dp,
)

val DefaultStyleTokens = MaaStyleTokens(
    radii = MaaRadii(
        card = 12.dp,
        button = 10.dp,
        pill = 20.dp,
        inner = 8.dp,
        segment = 4.dp,
        large = 12.dp,
    ),
    cardElevation = 1.dp,
)

/**
 * Semi Design 默认主题圆角（@douyinfe/semi-theme-default global.scss）
 * small=3 控件/chip；medium=6 菜单与卡片面；large=12 大容器；full 胶囊
 * elevation 0 = 平面优先，层级靠 outline + surface 底色
 */
val SemiStyleTokens = MaaStyleTokens(
    radii = MaaRadii(
        card = 6.dp,
        button = 3.dp,
        pill = 9999.dp,
        inner = 3.dp,
        segment = 2.dp,
        large = 12.dp,
    ),
    cardElevation = 0.dp,
)

fun styleTokensOf(style: ThemeStyle): MaaStyleTokens = when (style) {
    ThemeStyle.DEFAULT -> DefaultStyleTokens
    ThemeStyle.SEMI_DESIGN -> SemiStyleTokens
}
