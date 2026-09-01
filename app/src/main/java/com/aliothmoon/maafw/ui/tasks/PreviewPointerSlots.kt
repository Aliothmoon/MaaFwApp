package com.aliothmoon.maafw.ui.tasks

import com.aliothmoon.maafw.runner.MAX_PREVIEW_CONTACTS

/**
 * 预览手动触控：Compose 的 PointerId（单调递增 Long）→ 注入用 contact
 *
 * **从高位往低分配**：槽位表在特权进程里与 MaaFramework 的注入共用一张，而 fw 从 0 起用
 * （老版本不填 contact 时恒为 0）。同号撞上时 TouchPointerSequence 会先整体 CANCEL，
 * 把 fw 正在进行的手势一并丢掉，所以两边从两头相向取用
 */
class PreviewPointerSlots {

    private val owners = LongArray(MAX_PREVIEW_CONTACTS) { NONE }
    private val lastX = IntArray(MAX_PREVIEW_CONTACTS)
    private val lastY = IntArray(MAX_PREVIEW_CONTACTS)

    /** 幂等；满了返回 -1 */
    fun acquire(pointerId: Long): Int {
        val existing = indexOf(pointerId)
        if (existing >= 0) return existing
        val free = owners.indexOfLast { it == NONE }
        if (free >= 0) owners[free] = pointerId
        return free
    }

    fun indexOf(pointerId: Long): Int = owners.indexOf(pointerId)

    /** 未占有返回 -1 */
    fun release(pointerId: Long): Int {
        val index = indexOf(pointerId)
        if (index >= 0) owners[index] = NONE
        return index
    }

    fun remember(contact: Int, x: Int, y: Int) {
        lastX[contact] = x
        lastY[contact] = y
    }

    /** 交出仍占着的槽位与它们最后的位置并清空，供离开预览时逐个抬起 */
    fun releaseHeld(action: (contact: Int, x: Int, y: Int) -> Unit) {
        for (contact in owners.indices) {
            if (owners[contact] == NONE) continue
            owners[contact] = NONE
            action(contact, lastX[contact], lastY[contact])
        }
    }

    private companion object {
        const val NONE = -1L
    }
}
