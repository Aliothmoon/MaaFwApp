package com.aliothmoon.maafw.privileged

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

private const val SHORT_CAUSE_MAX = 120

/** Root 没有「运行」一说，su 不在就是不可用；Shizuku 不可用几乎都是服务没起 */
fun remoteAccessMessage(backend: RemoteBackend, failure: RemoteAccessFailure): UiText = when (failure) {
    RemoteAccessFailure.UNAVAILABLE ->
        if (backend == RemoteBackend.SHIZUKU) {
            uiTextOf(R.string.msg_backend_not_running, backend.display)
        } else {
            uiTextOf(R.string.msg_backend_unavailable, backend.display)
        }

    RemoteAccessFailure.NOT_GRANTED -> uiTextOf(R.string.msg_backend_auth_rejected, backend.display)
}

/** 连接器的 message 后半截是 launcher 日志尾巴，已进 Timber 与 service_boot_debug.log，这里只留一句 */
fun ServiceConnectResult.Failed.message(): UiText = uiTextOf(
    R.string.msg_backend_connect_failed,
    backend.display,
    error.message?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }?.take(SHORT_CAUSE_MAX)
        ?: error.javaClass.simpleName,
)

/** 连接路径上抛出来的异常 → 文案；认不出的返回 null，由调用方决定怎么兜 */
fun Throwable.remoteAccessMessageOrNull(): UiText? =
    (this as? RemoteAccessException)?.let { remoteAccessMessage(it.backend, it.failure) }
