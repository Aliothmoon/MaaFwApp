package com.aliothmoon.maafw.notification.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.RunProgressSnapshots
import com.aliothmoon.maafw.notification.canRequestPromotedOngoing
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.island.model.TextInfo
import timber.log.Timber

/**
 * 运行通知的一帧内容；与展示方式无关，由 [RunNotificationFactory] 按方式拼成通知
 *
 * @param progress 0..[RunProgressSnapshots.PROGRESS_MAX]
 * @param timeoutMs 非空时到点自动撤掉（测试通知用）；运行中的那条跟着前台服务走，不设
 */
data class RunNotificationContent(
    val title: String,
    val text: String?,
    val progressLabel: String?,
    val taskLabel: String?,
    val statusLine: String?,
    val progress: Int,
    val indeterminate: Boolean,
    val barColor: Int,
    val timeoutMs: Long? = null,
) {
    val percent: Int?
        get() = if (indeterminate) null else (progress * 100 / RunProgressSnapshots.PROGRESS_MAX).coerceIn(0, 100)
}

/**
 * 按展示方式拼运行通知（移植自 MaaMeow 的 LiveNotificationFactory + HyperOsFocusPublisher）
 *
 * - 标准通知栏：常驻 + 进度条，不请求实时更新
 * - 实时更新：再请求 promoted ongoing 并给状态栏胶囊短文本
 * - 超级岛：走独立的高重要性无声频道（低重要性的通知系统不给浮岛），塞焦点通知模板
 *
 * 不做 MaaMeow 那套样式自定义（胶囊内容、配色、图标），一律用它的默认档
 */
class RunNotificationFactory(
    context: Context,
    private val sequenceStore: FocusSequenceStore,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    @Volatile
    private var channelsReady = false

    /** 进度 1 秒一刷，每次都解码应用图标扛不住 */
    private val appIcon: Icon by lazy {
        val drawable = appContext.applicationInfo.loadIcon(appContext.packageManager)
        Icon.createWithBitmap(if (drawable is BitmapDrawable) drawable.bitmap else drawable.toBitmap())
    }

    private val appLabel: String by lazy {
        appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
    }

    fun ensureChannels() {
        if (channelsReady) return
        manager.createNotificationChannel(
            NotificationChannel(
                RUN_CHANNEL_ID,
                appContext.getString(R.string.notification_channel_run),
                // LOW：常驻不该出声。MIN 进不了状态栏，部分 ROM 还当成前台服务不成立；
                // 实时更新也只禁 MIN。重要性建成就改不了，沿用 run_execution
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.notification_channel_run_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ISLAND_CHANNEL_ID,
                appContext.getString(R.string.notification_channel_run_island),
                // 岛要 HIGH 才浮得出来；声音与振动在频道上关掉，通知上不能 setSilent（会压掉浮出）
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.notification_channel_run_island_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelsReady = true
    }

    /**
     * @param firstFloat 超级岛：这一轮第一次发时让岛浮出来一下，之后的刷新不再打扰
     */
    fun build(
        content: RunNotificationContent,
        backend: LiveBackend,
        notifyId: Int,
        firstFloat: Boolean = false,
    ): Notification {
        ensureChannels()
        val island = backend == LiveBackend.HYPER_ISLAND
        val builder = NotificationCompat.Builder(appContext, if (island) ISLAND_CHANNEL_ID else RUN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(content.barColor)
            .setContentTitle(content.title)
            .setContentText(content.text?.takeIf { it.isNotBlank() })
            .setContentIntent(contentIntent(notifyId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // ProgressStyle 只在 36+ 生效；经典模板仍靠 setProgress，否则 9–15 没有条子
            .setProgress(RunProgressSnapshots.PROGRESS_MAX, content.progress, content.indeterminate)
        content.timeoutMs?.let(builder::setTimeoutAfter)
        if (island) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            runCatching { islandExtras(content, notifyId, firstFloat) }
                .onFailure { Timber.e(it, "Failed to build HyperOS focus extras") }
                .getOrNull()
                ?.let(builder::addExtras)
        } else {
            builder.setSilent(true).setStyle(progressStyle(content))
            if (backend == LiveBackend.LIVE_UPDATE) {
                builder.setRequestPromotedOngoing(manager.canRequestPromotedOngoing())
                content.progressLabel?.let(builder::setShortCriticalText)
            }
        }
        return builder.build()
    }

    private fun progressStyle(content: RunNotificationContent): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressIndeterminate(content.indeterminate)
            .setProgressTrackerIcon(IconCompat.createWithResource(appContext, R.drawable.ic_progress_tracker))
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(RunProgressSnapshots.PROGRESS_MAX).setColor(content.barColor),
            )
        if (!content.indeterminate) style.setProgress(content.progress)
        return style
    }

    /**
     * 焦点通知 V3 模板（岛的布局取 MaaMeow「任务名 + 进度」那一档）
     *
     * 大岛左栏：应用图标 + 任务名 / n/m；右栏：状态句 / 正文。小岛：图标 + 进度环。息屏显示百分比
     */
    private fun islandExtras(frame: RunNotificationContent, notifyId: Int, firstFloat: Boolean): Bundle {
        val percent = frame.percent
        val timeoutSec = frame.timeoutMs?.let { (it / 1000).toInt() } ?: ONGOING_TIMEOUT_SEC
        val headline = (frame.taskLabel ?: frame.title).take(40)
        val body = (frame.text?.takeIf { it.isNotBlank() } ?: frame.title).take(80)
        val status = frame.statusLine ?: frame.title
        val aod = percent?.let { "$it%" } ?: "…"
        return FocusNotification.buildV3 {
            val appPic = createPicture(PIC_APP, appIcon)
            business = BUSINESS_PROGRESS
            this.notifyId = notifyId.toString()
            updatable = true
            isShowNotification = true
            reopen = "reopen"
            timeout = (timeoutSec / 60).coerceAtLeast(5)
            sequence = sequenceStore.next(notifyId)
            aodTitle = aod
            ticker = "$headline $aod".trim().take(40)
            tickerPic = appPic
            filterWhenNoPermission = false
            showSmallIcon = false
            enableFloat = false
            islandFirstFloat = firstFloat
            hideDeco = false
            if (firstFloat) outEffectSrc = "glow"

            chatInfo {
                // 不设 picProfile：MIUI 会给它叠一个发送方应用角标，而头像本就是本应用图标
                title = headline
                this.content = body
            }
            if (percent != null) {
                multiProgressInfo {
                    progress = percent
                    color = PROGRESS_COLOR
                }
            }
            island {
                islandProperty = 1
                islandTimeout = timeoutSec
                dismissIsland = false
                islandOrder = false
                bigIslandArea {
                    imageTextInfoLeft {
                        type = 1
                        picInfo {
                            type = 1
                            pic = appPic
                        }
                        textInfo {
                            title = (frame.taskLabel ?: appLabel).take(16)
                            this.content = frame.progressLabel.orEmpty().take(8)
                            showHighlightColor = true
                        }
                    }
                    textInfo = TextInfo().apply {
                        title = status.take(18)
                        this.content = body.take(32)
                        showHighlightColor = true
                        narrowFont = true
                    }
                }
                smallIslandArea {
                    if (percent == null) {
                        picInfo {
                            type = 1
                            pic = appPic
                        }
                    } else {
                        combinePicInfo {
                            picInfo {
                                type = 1
                                pic = appPic
                            }
                            progressInfo {
                                progress = percent
                                colorReach = PROGRESS_COLOR
                                colorUnReach = PROGRESS_UNREACH
                                isCCW = true
                            }
                        }
                    }
                }
            }
        }
    }

    private fun contentIntent(notifyId: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            notifyId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** 沿用原来的常驻频道 id：用户在系统里调过的设置不能丢 */
        const val RUN_CHANNEL_ID = "run_execution"
        const val ISLAND_CHANNEL_ID = "run_execution_island"

        private const val ONGOING_TIMEOUT_SEC = 86_400
        private const val PIC_APP = "miui.focus.pic_progress_app"
        private const val BUSINESS_PROGRESS = "download_progress"
        private const val PROGRESS_COLOR = "#3482FF"
        private const val PROGRESS_UNREACH = "#33FFFFFF"
    }
}
