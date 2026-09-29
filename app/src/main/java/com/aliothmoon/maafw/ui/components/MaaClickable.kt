package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch

/**
 * 卡片与行的统一点击手感：按下高亮（[MaaPressIndication]）+ 按下时整块缩到 0.97
 *
 * 走 Modifier.Node 而非 `composed {}`：`composed` 每次组合都产出新 Modifier 实例，
 * 破坏 Modifier 相等性比较，调用点所在子树跟着失去跳过机会。
 * Node 的状态（InteractionSource、缩放动画）挂在节点上，重组只走 [ModifierNodeElement.update]
 *
 * 高亮不读 LocalIndication：主题外层的 MaterialTheme 会把它换成 ripple()，
 * 而原生 ripple 的宿主视图池会让按下反馈串到别的组件上，见 [MaaPressIndication]
 *
 * @param indication 是否挂按下高亮；卡片表头这类通栏点击区关掉——缩放已经够表达按下了
 * @param shape 高亮轮廓；本修饰符在组件形状裁剪之外（卡片、胶囊）时传入同一形状，否则会露出方角
 */
fun Modifier.maaClickable(
    enabled: Boolean = true,
    indication: Boolean = true,
    shape: Shape = RectangleShape,
    onClick: () -> Unit,
): Modifier = this then MaaClickableElement(enabled, indication, shape, onClick)

private const val PressedScale = 0.97f

private val PressSpring = spring<Float>(
    // NoBouncy：按压反馈要干脆回弹，弹跳会让松手后还在晃、和后续动画叠出钝感
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

private data class MaaClickableElement(
    val enabled: Boolean,
    val indication: Boolean,
    val shape: Shape,
    val onClick: () -> Unit,
) : ModifierNodeElement<MaaClickableNode>() {

    override fun create(): MaaClickableNode = MaaClickableNode(enabled, indication, shape, onClick)

    override fun update(node: MaaClickableNode) {
        node.update(enabled, indication, shape, onClick)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "maaClickable"
        properties["enabled"] = enabled
        properties["indication"] = indication
    }
}

private class MaaClickableNode(
    private var enabled: Boolean,
    private var indication: Boolean,
    private var shape: Shape,
    private var onClick: () -> Unit,
) : DelegatingNode(),
    LayoutModifierNode,
    SemanticsModifierNode {

    private val interactionSource = MutableInteractionSource()
    private var scale = Animatable(1f)

    /** 未配对 Release/Cancel 的按下；节点被复用时残留会让按下高亮出现在另一张卡上 */
    private var pendingPress: PressInteraction.Press? = null

    // 禁用时整个撤掉指针节点，而不是在回调里判 enabled：
    // detectTapGestures 会消费 down，留着会让禁用的卡片挡住外层列表的滚动
    private var pointerNode: SuspendingPointerInputModifierNode? = null

    private var indicationNode: DelegatableNode? = null

    override fun onAttach() {
        syncPointerNode()
        syncIndication()
    }

    /** detach 时 coroutineScope 已被取消，补发只能走 tryEmit */
    override fun onDetach() {
        cancelPendingPress()
        super.onDetach()
    }

    override fun onReset() {
        cancelPendingPress()
        // Animatable 没有非挂起的复位入口，直接换一个新的——此刻动画协程已随 detach 取消
        scale = Animatable(1f)
        super.onReset()
    }

    private fun cancelPendingPress() {
        val press = pendingPress ?: return
        pendingPress = null
        interactionSource.tryEmit(PressInteraction.Cancel(press))
    }

    fun update(enabled: Boolean, indication: Boolean, shape: Shape, onClick: () -> Unit) {
        this.onClick = onClick
        if (this.indication != indication || this.shape != shape) {
            this.indication = indication
            this.shape = shape
            syncIndication()
        }
        if (this.enabled == enabled) return
        this.enabled = enabled
        syncPointerNode()
        invalidateSemantics()
        if (!enabled) {
            // 按下中途被禁用时手动收回缩放，否则卡片会停在 0.97
            coroutineScope.launch { scale.animateTo(1f, PressSpring) }
        }
    }

    private fun syncPointerNode() {
        val current = pointerNode
        if (enabled && current == null) {
            pointerNode = delegate(SuspendingPointerInputModifierNode { detectPress() })
        } else if (!enabled && current != null) {
            undelegate(current)
            pointerNode = null
        }
    }

    private suspend fun PointerInputScope.detectPress() {
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                pendingPress = press
                // 一律另起协程：emit 与动画都会挂起，卡在这里就来不及等 tryAwaitRelease，
                // 快速点击会被吞掉
                coroutineScope.launch { interactionSource.emit(press) }
                coroutineScope.launch { scale.animateTo(PressedScale, PressSpring) }
                val released = tryAwaitRelease()
                // 先清标记再补发：onDetach 抢在前面时由它 tryEmit Cancel，不能两边都发
                if (pendingPress === press) {
                    pendingPress = null
                    coroutineScope.launch {
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                    }
                }
                coroutineScope.launch { scale.animateTo(1f, PressSpring) }
            },
            onTap = { onClick() },
        )
    }

    private fun syncIndication() {
        indicationNode?.let { undelegate(it) }
        indicationNode = if (indication) {
            delegate(MaaPressIndication(shape).create(interactionSource))
        } else {
            null
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // 缩放读在图层块里：动画只触发图层失效，不重新测量也不重组
            placeable.placeWithLayer(0, 0) {
                val value = scale.value
                scaleX = value
                scaleY = value
            }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (enabled) {
            onClick {
                this@MaaClickableNode.onClick()
                true
            }
        } else {
            disabled()
        }
    }
}
