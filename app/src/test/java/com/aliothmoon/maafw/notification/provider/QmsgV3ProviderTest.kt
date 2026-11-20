package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.notification.NotificationSettings
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.util.HttpClientHelper
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QmsgV3ProviderTest {

    private val httpClient = mockk<HttpClientHelper>()
    private val url = slot<String>()
    private val body = slot<String>()

    private val configured = NotificationSettings(qmsgV3Key = "k")

    private fun send(
        settings: NotificationSettings = configured,
        content: String = "c",
        response: String = """{"success":true,"message":"操作成功","code":0,"data":42}""",
    ): NotificationSendResult {
        coEvery { httpClient.post(capture(url), capture(body), any(), any()) } answers {
            Response.Builder()
                .request(Request.Builder().url(firstArg<String>()).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("mock")
                .body(response.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val manager = mockk<NotificationSettingsManager> { coEvery { current() } returns settings }
        return runBlocking { QmsgV3Provider(httpClient, manager).send("t", content) }
    }

    @Test
    fun emptyKeyFails() {
        val result = send(settings = NotificationSettings())
        assertTrue((result as NotificationSendResult.Failed).message.isResource(R.string.notification_err_qmsg_key_empty))
    }

    @Test
    fun postsToV3JsonEndpoint() {
        assertEquals(NotificationSendResult.Success, send())
        assertEquals("https://qmsg.zendee.cn/v3/jsend/k", url.captured)
    }

    @Test
    fun omitsGroupWhenBlank() {
        send(settings = configured.copy(qmsgV3Group = " "))
        assertEquals(setOf("msg"), Json.parseToJsonElement(body.captured).jsonObject.keys)
    }

    @Test
    fun sendsGroupWhenSet() {
        send(settings = configured.copy(qmsgV3Group = "123456789"))
        assertEquals("123456789", Json.parseToJsonElement(body.captured).jsonObject["group"]!!.jsonPrimitive.content)
    }

    @Test
    fun longContentFitsTheLimit() {
        send(content = "x".repeat(5_000))
        assertTrue(Json.parseToJsonElement(body.captured).jsonObject["msg"]!!.jsonPrimitive.content.length <= QMSG_MAX_MSG_LENGTH)
    }

    /** 业务失败照样是 HTTP 200，原因在 reason 里 */
    @Test
    fun rejectionCarriesTheReason() {
        val result = send(response = """{"success":false,"reason":"key 无效","code":500}""")
        assertTrue((result as NotificationSendResult.Failed).message.isResource(R.string.notification_err_rejected, "key 无效"))
    }
}
