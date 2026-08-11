package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.fastAny
import com.aliothmoon.maafw.ui.rememberInputFocusManager

/** 一次手势内的按压与已清标记；在 pointer input 与 nested scroll 两条回调之间传，两者都不在组合里跑 */
private class GestureState {
    var down = false
    var cleared = false
}

/**
 * 点空白、点输入框外、用户滚动时清输入焦点
 *
 * 主窗口在 `AppRoot` 根部铺了一层，普通页面不必再加；
 * 只有另开窗口（Dialog / sheet）或自己截断了命中测试的那一层，才需要在自己这边补挂
 */
@Composable
fun Modifier.clearFocusOnBlankTap(): Modifier {
    val hostView = LocalView.current
    val latestClear by rememberUpdatedState(rememberInputFocusManager())
    var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val gesture = remember { GestureState() }

    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 必须有手指按着。输入框拿到焦点后的 bringIntoView 与 IME 起落引发的 relocate
                // 走的也是 UserInput 这条源，只看 source 会在刚点进输入框时就把焦点清掉；
                // 这两者都发生在抬手之后，真手势滚动时手指一定还在屏上
                // cleared 拦住同一次拖拽的后续帧，onPreScroll 是每帧都来的
                if (gesture.down && !gesture.cleared && available != Offset.Zero &&
                    source == NestedScrollSource.UserInput
                ) {
                    gesture.cleared = true
                    latestClear.clear()
                }
                return Offset.Zero
            }
        }
    }

    return this
        .onGloballyPositioned { layoutCoordinates = it }
        .nestedScroll(scrollConnection)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Initial pass 排在所有子节点之前，谁都还没来得及消费，这里看到的按压状态最全
                    gesture.down = event.changes.fastAny { it.pressed }
                    if (event.type != PointerEventType.Press) continue
                    gesture.cleared = false
                    // 仅 Android 文本编辑器 (悬浮窗 EditText); Compose TextField 的 findFocus 通常是整窗
                    val focused = hostView.findFocus() ?: continue
                    if (focused === hostView || !focused.onCheckIsTextEditor()) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val coords = layoutCoordinates ?: continue
                    if (!coords.isAttached) continue
                    val posInWindow = coords.localToWindow(change.position)
                    val loc = IntArray(2)
                    focused.getLocationInWindow(loc)
                    val x = posInWindow.x.toInt()
                    val y = posInWindow.y.toInt()
                    val inside = x in loc[0] until (loc[0] + focused.width) &&
                        y in loc[1] until (loc[1] + focused.height)
                    if (!inside) {
                        latestClear.clear()
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures {
                latestClear.clear()
            }
        }
}
