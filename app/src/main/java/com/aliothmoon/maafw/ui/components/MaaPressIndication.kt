package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * 全局唯一的按下反馈：按下时在组件轮廓内铺一层内容色，松手淡出
 *
 * 不用 Material 的 ripple()：它在 Android 上借窗口级的 RippleHostView 池（每窗口 5 个）画原生
 * RippleDrawable，池满后轮转把宿主转给新按下的组件，旧组件缓存的绘制指令里还留着那个宿主，
 * 于是一个组件按下时，之前按过的组件跟着同步亮、同步灭。这里的状态完全挂在各自节点上，
 * 节点脱离或复用即清零，不存在跨组件共享的东西
 *
 * [shape] 决定高亮的轮廓；调用点在形状裁剪之外时（如卡片、胶囊）传入同一形状，避免露出方角
 */
class MaaPressIndication(private val shape: Shape = RectangleShape) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        MaaPressIndicationNode(interactionSource, shape)

    override fun equals(other: Any?): Boolean = other is MaaPressIndication && other.shape == shape

    override fun hashCode(): Int = shape.hashCode()
}

/** M3 按下态的状态层不透明度 */
private const val PressedAlpha = 0.10f
private val PressIn = tween<Float>(durationMillis = 80)
private val PressOut = tween<Float>(durationMillis = 150)

private class MaaPressIndicationNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var progress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            // 同一节点可能叠着几次按下（多指），全部松开才淡出
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    else -> return@collect
                }
                val pressed = presses.isNotEmpty()
                launch { progress.animateTo(if (pressed) 1f else 0f, if (pressed) PressIn else PressOut) }
            }
        }
    }

    /** 脱离时动画协程已随作用域取消，残留的中间值不能带到复用后的下一个组件上 */
    override fun onDetach() {
        progress = Animatable(0f)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val value = progress.value
        if (value <= 0f) return
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            color = currentValueOf(LocalContentColor),
            alpha = PressedAlpha * value,
        )
    }
}
