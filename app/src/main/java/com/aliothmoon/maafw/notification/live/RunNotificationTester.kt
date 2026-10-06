package com.aliothmoon.maafw.notification.live

import android.app.NotificationManager
import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.notification.RunProgressSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 通知设置页的「发送测试」：按当前生效的展示方式发一条假进度，15 秒后自己消失
 *
 * 和真正的运行通知走同一个 [RunNotificationFactory]，看到的就是跑起来时的样子；
 * 超级岛只断一下小米推送的网（[XmsfNetworkGate.pulse]），盖住发出瞬间的云端鉴权
 */
class RunNotificationTester(
    context: Context,
    private val probe: LiveCapabilityProbe,
    private val factory: RunNotificationFactory,
    private val gate: XmsfNetworkGate,
    private val scope: CoroutineScope,
) {
    private val manager = context.applicationContext.getSystemService(NotificationManager::class.java)

    fun send(title: String, text: String) {
        scope.launch(MaaDispatchers.IO) {
            val backend = probe.refresh().backend
            val content = RunNotificationContent(
                title = title,
                text = "$SAMPLE_PROGRESS · $text",
                progressLabel = SAMPLE_PROGRESS,
                taskLabel = title,
                statusLine = text,
                progress = RunProgressSnapshots.PROGRESS_MAX * 3 / 5,
                indeterminate = false,
                barColor = RunProgressSnapshots.BAR_COLOR,
                timeoutMs = TIMEOUT_MS,
            )
            val notification = factory.build(content, backend, TEST_NOTIFICATION_ID, firstFloat = true)
            val post = {
                runCatching { manager.notify(TEST_NOTIFICATION_ID, notification) }
                    .onFailure { Timber.w(it, "Failed to post the run notification test") }
                Unit
            }
            if (backend == LiveBackend.HYPER_ISLAND) gate.pulse(post) else post()
        }
    }

    private companion object {
        /** 与运行通知 1001、PI 模板通知 1100 起的那段隔开 */
        const val TEST_NOTIFICATION_ID = 1005
        const val TIMEOUT_MS = 15_000L
        const val SAMPLE_PROGRESS = "3/5"
    }
}
