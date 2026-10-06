package com.aliothmoon.maafw.bridge

import android.os.RemoteException
import android.view.KeyCharacterMap
import com.aliothmoon.maafw.ITextInputSink
import com.aliothmoon.maafw.constant.TextInputResult
import com.aliothmoon.maafw.third.Ln

/**
 * 不走输入法：要换掉系统输入法，和用户自己的冲突；不走剪贴板：会覆盖用户剪贴板，密码也会进剪贴板历史
 */
object TextInputDispatcher {

    private const val TAG = "TextInput"

    @Volatile
    var sink: ITextInputSink? = null

    private val keyCharacterMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    @JvmStatic
    fun input(text: String, displayId: Int, targetPackage: String?): Boolean {
        if (text.isEmpty()) return true
        // 有一个字符映射不出来就整串返回 null
        val events = keyCharacterMap.getEvents(text.toCharArray())
        if (events != null) {
            Ln.i("$TAG: route=keys chars=${text.length} events=${events.size} displayId=$displayId")
            return InputControlUtils.injectTextKeys(events, displayId)
        }
        return viaAccessibility(text, displayId, targetPackage.orEmpty())
    }

    private fun viaAccessibility(text: String, displayId: Int, targetPackage: String): Boolean {
        val current = sink
        if (current == null) {
            Ln.w("$TAG: route=accessibility but no sink registered, app process may be gone")
            return false
        }
        val result = try {
            current.setText(displayId, targetPackage, text)
        } catch (e: RemoteException) {
            Ln.w("$TAG: text input sink died", e)
            sink = null
            return false
        }
        Ln.i("$TAG: route=accessibility chars=${text.length} displayId=$displayId result=$result")
        return result == TextInputResult.OK
    }
}
