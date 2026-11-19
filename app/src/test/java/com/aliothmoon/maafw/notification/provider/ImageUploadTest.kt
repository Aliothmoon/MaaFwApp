package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUploadTest {

    private class TextOnly : NotificationProvider {
        override val id = "fake"
        var textSends = 0

        override suspend fun send(title: String, content: String): NotificationSendResult {
            textSends++
            return NotificationSendResult.Success
        }
    }

    /** 附件被拒就退回纯文字，别让一张图把整条推送弄丢 */
    @Test
    fun `a rejected image falls back to text`() = runTest {
        val provider = TextOnly()

        val result = provider.sendWithTextFallback("t", "c") {
            NotificationSendResult.Failed(uiTextOf(R.string.notification_err_network))
        }

        assertEquals(NotificationSendResult.Success, result)
        assertEquals(1, provider.textSends)
    }

    /** 网络异常默认不重发：对端可能其实已经收到 */
    @Test
    fun `a transient failure is not resent unless asked`() = runTest {
        val transient = NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        val provider = TextOnly()

        assertEquals(transient, provider.sendWithTextFallback("t", "c") { transient })
        assertEquals(0, provider.textSends)

        provider.sendWithTextFallback("t", "c", retryOnTransient = true) { transient }
        assertEquals(1, provider.textSends)
    }
}
