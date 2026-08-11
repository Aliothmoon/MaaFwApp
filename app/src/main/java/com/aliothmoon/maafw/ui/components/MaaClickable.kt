package com.aliothmoon.maafw.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch

/**
 * 卡片与行的统一点击手感：涟漪 + 按下时整块缩到 0.97
 *
 * 走 Modifier.Node 而非 `composed {}`：`composed` 每次组合都产出新 Modifier 实例，
 * 破坏 Modifier 相等性比较，调用点所在子树跟着失去跳过机会。
 * Node 的状态（InteractionSource、缩放动画）挂在节点上，重组只走 [ModifierNodeElement.update]
 *
 * @param indication 是否挂涟漪；卡片表头这类通栏点击区关掉——涟漪要从触点铺满整张卡的宽度，
 *   比缩放本身还抢眼，而缩放已经够表达按下了
 */
fun Modifier.maaClickable(
    enabled: Boolean = true,
    indication: Boolean = true,
    onClick: () -> Unit,
): Modifier = this then MaaClickableElement(enabled, indication, onClick)

private const val PressedScale = 0.97f

private val PressSpring = spring<Float>(
    // NoBouncy：按压反馈要干脆回弹，弹跳会让松手后还在晃、和后续动画叠出钝感
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

private data class MaaClickableElement(
    val enabled: Boolean,
    val indication: Boolean,
    val onClick: () -> Unit,
) : ModifierNodeElement<MaaClickableNode>() {

    override fun create(): MaaClickableNode = MaaClickableNode(enabled, indication, onClick)

    override fun update(node: MaaClickableNode) {
        node.update(enabled, indication, onClick)
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
    private var onClick: () -> Unit,
) : DelegatingNode(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    LayoutModifierNode,
    SemanticsModifierNode {

    private val interactionSource = MutableInteractionSource()
    private var scale = Animatable(1f)

    /** 未配对 Release/Cancel 的按下；节点被复用时残留会让涟漪出现在另一张卡上 */
    private var pendingPress: PressInteraction.Press? = null

    // 禁用时整个撤掉指针节点，而不是在回调里判 enabled：
    // detectTapGestures 会消费 down，留着会让禁用的卡片挡住外层列表的滚动
    private var pointerNode: SuspendingPointerInputModifierNode? = null

    private var indicationNode: DelegatableNode? = null
    private var attachedIndication: Indication? = null

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

    fun update(enabled: Boolean, indication: Boolean, onClick: () -> Unit) {
        this.onClick = onClick
        if (this.indication != indication) {
            this.indication = indication
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

    /** LocalIndication 由主题提供，可能随主题切换而变，所以经 observeReads 跟踪 */
    private fun syncIndication() {
        if (!indication) {
            indicationNode?.let { undelegate(it) }
            indicationNode = null
            attachedIndication = null
            return
        }
        observeReads {
            val current = currentValueOf(LocalIndication)
            if (current === attachedIndication) return@observeReads
            attachedIndication = current
            indicationNode?.let { undelegate(it) }
            // 非 IndicationNodeFactory 的老式 Indication 无法挂进节点树，此时不画涟漪
            indicationNode = (current as? IndicationNodeFactory)
                ?.create(interactionSource)
                ?.also { delegate(it) }
        }
    }

    override fun onObservedReadsChanged() {
        syncIndication()
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
