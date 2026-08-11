package com.aliothmoon.maafw.semiicons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/**
 * Semi 图标加载入口
 *
 * - [mono]：单色 vector，配合 `Icon(..., tint=…)`
 * - [lab]：彩色 lab，调用方应 `tint = Color.Unspecified`，不要再改色
 *
 * 资源索引见 [SemiIconRes]；业务语义别名（随主题切 Material）在 app 的 `MaaIcons`
 */
object SemiIcons {

    @Composable
    fun mono(@DrawableRes resId: Int): ImageVector = ImageVector.vectorResource(resId)

    @Composable
    fun lab(@DrawableRes resId: Int): ImageVector = ImageVector.vectorResource(resId)
}
