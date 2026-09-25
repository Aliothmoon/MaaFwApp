package com.aliothmoon.maafw.bridge

/**
 * 由当前按下的 contact 集合，算出这一次 down/move/up 该发哪种 MotionEvent
 *
 * 与 `android.view.MotionEvent` 的 `ACTION_*` 同值
 */
object TouchPointerSequence {

    /** Android MotionEvent pointer id 取值 0..15，与 MaaFramework validate_contact 一致 */
    const val MAX_CONTACTS = 16

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6

    enum class Kind { Down, Move, Up }

    enum class FailureReason { InvalidContact, MissingContact, TooManyContacts }

    data class Pointer(
        val contact: Int,
        val x: Float,
        val y: Float,
        /** Android pointerId 与调用方 contact 分开；首指从 0 开始，按住期间保持不变。 */
        val pointerId: Int = contact,
    )

    data class Step(
        val ok: Boolean,
        val actionMasked: Int = 0,
        val changingIndex: Int = 0,
        val pointers: List<Pointer> = emptyList(),
        val cancelFirst: Boolean = false,
        val failureReason: FailureReason? = null,
    )

    fun plan(
        kind: Kind,
        current: List<Pointer>,
        contact: Int,
        x: Float,
        y: Float,
    ): Step {
        if (contact !in 0..<MAX_CONTACTS) {
            return Step(ok = false, failureReason = FailureReason.InvalidContact)
        }
        val idx = current.indexOfFirst { it.contact == contact }
        val pointerId = current.getOrNull(idx)?.pointerId
            ?: (0 until MAX_CONTACTS).firstOrNull { id -> current.none { it.pointerId == id } }
            ?: return Step(ok = false)
        val nextPointer = Pointer(contact, x, y, pointerId)
        return when (kind) {
            Kind.Down -> when {
                // 同一手指重复按下：上一序列未正常结束，先整体 CANCEL 再开新手势
                idx >= 0 -> Step(
                    ok = true,
                    actionMasked = ACTION_DOWN,
                    pointers = listOf(nextPointer.copy(pointerId = 0)),
                    cancelFirst = true,
                )

                current.isEmpty() -> Step(
                    ok = true,
                    actionMasked = ACTION_DOWN,
                    pointers = listOf(nextPointer),
                )

                current.size >= MAX_CONTACTS -> {
                    Step(ok = false, failureReason = FailureReason.TooManyContacts)
                }
                else -> {
                    val next = current + nextPointer
                    Step(
                        ok = true,
                        actionMasked = ACTION_POINTER_DOWN,
                        changingIndex = next.lastIndex,
                        pointers = next,
                    )
                }
            }

            Kind.Move -> {
                if (idx < 0) {
                    return Step(ok = false, failureReason = FailureReason.MissingContact)
                }
                val next = current.toMutableList()
                next[idx] = nextPointer
                Step(ok = true, actionMasked = ACTION_MOVE, changingIndex = idx, pointers = next)
            }

            Kind.Up -> {
                if (idx < 0) {
                    return Step(ok = false, failureReason = FailureReason.MissingContact)
                }
                val next = current.toMutableList()
                next[idx] = nextPointer
                if (current.size == 1) {
                    Step(ok = true, actionMasked = ACTION_UP, pointers = next)
                } else {
                    Step(
                        ok = true,
                        actionMasked = ACTION_POINTER_UP,
                        changingIndex = idx,
                        pointers = next,
                    )
                }
            }
        }
    }
}
