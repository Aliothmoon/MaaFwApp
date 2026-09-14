package com.aliothmoon.maafw.ui

import android.app.Application
import android.widget.Toast
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.session.SessionEffect
import com.aliothmoon.maafw.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级 [SessionEffect.ShowMessage] 出口：Application 上下文的 Toast，
 * Activity 停着、人在目标应用里也能看见
 *
 * 其它 Effect 仍由 [AppRoot] 收
 */
class SessionMessagePresenter(
    private val context: Application,
    private val session: SessionViewModel,
    private val scope: CoroutineScope,
) {
    fun setup() {
        scope.launch {
            session.effects.collect { effect ->
                val message = (effect as? SessionEffect.ShowMessage)?.message ?: return@collect
                val text = message.resolve(context)
                if (text.isBlank()) return@collect
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
