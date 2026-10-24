package com.aliothmoon.maafw.ui.tasks

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.resolve
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.runner.RunLogEntry
import kotlin.math.roundToInt
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 双列时一排两格：竖屏下每格的画面还看得清，再多就只剩色块了 */
private const val InstancesPerGridRow = 2

/** 单列时一排一格：画面铺满宽度，超出往下滚 */
private const val InstancesPerSingleRow = 1

/** 每格下方带几行日志；两行是那一屏格子高度还容得下的上限 */
private const val InstanceLogLines = 2

/** 没有实例日志时的占位流：省得为了空值每次重组都新建一条 */
private val EmptyInstanceLogs = MutableStateFlow<Map<Int, List<RunLogEntry>>>(emptyMap())

/**
 * 全部实例
 *
 * 这一屏只回答一个问题：**哪一路现在是什么样**。所以只放画面、实例名与（可选的）最近两行日志——
 * 任务列表、条件配置这些是「要操作某一路」时才需要的东西，堆在这里只会挡住画面。
 *
 * 单击**多选**（再点一下已选中的格子则取消；点别的格子不取消之前的选择，可同时选中多路，
 * 一次「开始任务」批量启动选中的所有实例），双击进入该实例的常规界面。
 * 两个手势交给 [detectTapGestures] 区分：单击要等双击判定窗口过去才成立，所以「间隔一会的
 * 第二下」会被当成第二次单击（=取消选中），而「快速两下」才是双击进入——靠这层时序自然区分。
 *
 * 排布由 [twoColumn] 决定：双列一屏看全，单列每格更大、装不下就滚。
 */
@Composable
internal fun AllInstancesPanel(
    resolution: DisplayResolution?,
    selectedSlots: Set<Int>,
    onToggleSlot: (Int) -> Unit,
    onEnter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 双列显示实例；关掉即单列，一格一行、超出滚动 */
    twoColumn: Boolean = true,
    /** 每格下方是否带该实例最近两行日志 */
    showLogs: Boolean = false,
    /**
     * 实例号 → 该路最近几行；由 [com.aliothmoon.maafw.runner.RunLogRecorder] 按路记
     *
     * 传流而不是值：这一屏退出后没人订阅它，整棵树就不该跟着日志频率重组
     */
    instanceLogs: StateFlow<Map<Int, List<RunLogEntry>>> = EmptyInstanceLogs,
) {
    val slots = RemoteServiceManager.activeSlots
    val logsBySlot by instanceLogs.collectAsStateWithLifecycle()
    val perRow = if (twoColumn) InstancesPerGridRow else InstancesPerSingleRow
    Column(
        // 单列时五格必然超一屏，双列也可能；滚动本身在放得下时不出现，两种排布共用一条
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        // 按序排：实例在前，[perRow] 格一行
        slots.chunked(perRow).forEach { rowSlots ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                rowSlots.forEach { slot ->
                    InstancePreviewCell(
                        slot = slot,
                        selected = slot in selectedSlots,
                        resolution = resolution,
                        showLogs = showLogs,
                        logLines = if (showLogs) logsBySlot[slot].orEmpty() else emptyList(),
                        onToggle = { onToggleSlot(slot) },
                        onEnter = { onEnter(slot) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐一行的空位，最后一行的格子才不会横向拉宽（单列时本就没有空位）
                repeat(perRow - rowSlots.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InstancePreviewCell(
    slot: Int,
    selected: Boolean,
    resolution: DisplayResolution?,
    showLogs: Boolean,
    logLines: List<RunLogEntry>,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 每格轮询自己这路的 FPS：任务页左上角那个只跟选中路，这里五路各自显示
    var fps by remember(slot) { mutableStateOf<Float?>(null) }
    LaunchedEffect(slot) {
        while (true) {
            fps = withContext(Dispatchers.IO) {
                runCatching {
                    RemoteServiceManager.slotConnectionOrNull(slot)?.serviceOrNull()?.getGameFps()
                }.getOrNull()?.takeIf { it >= 0f }
            }
            delay(1_000L)
        }
    }
    Surface(
        modifier = modifier.pointerInput(slot) {
            detectTapGestures(onTap = { onToggle() }, onDoubleTap = { onEnter() })
        },
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
        color = scheme.surfaceVariant,
        border = BorderStroke(
            if (selected) MaaDesignTokens.Border.selected else MaaDesignTokens.Separator.thickness,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(resolution?.aspectRatio ?: DefaultAspectRatio),
                contentAlignment = Alignment.Center,
            ) {
                InstanceSurface(slot = slot)
                fps?.let { value ->
                    Text(
                        text = "${value.roundToInt()} FPS",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = stringResource(R.string.quick_instance_label, instanceOrdinal(slot + 1)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaaDesignTokens.Spacing.sm,
                        vertical = MaaDesignTokens.Spacing.xs,
                    ),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (showLogs) {
                InstanceLogLines(
                    entries = logLines,
                    modifier = Modifier.padding(
                        start = MaaDesignTokens.Spacing.sm,
                        end = MaaDesignTokens.Spacing.sm,
                        bottom = MaaDesignTokens.Spacing.xs,
                    ),
                )
            }
        }
    }
}

/**
 * 这一路的最近两行，短的一行也占一行位
 *
 * 固定行数而不是「有几行显示几行」：五格并排时高度参差比空一行更难看，
 * 而且日志一跳出来就会把下面的东西顶走
 */
@Composable
private fun InstanceLogLines(entries: List<RunLogEntry>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shown = entries.takeLast(InstanceLogLines)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LOG_LINE_GAP),
    ) {
        repeat(InstanceLogLines) { index ->
            val entry = shown.getOrNull(index)
            Text(
                text = entry?.text?.resolve(context).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val LOG_LINE_GAP = 2.dp

/** 还不知道虚拟屏分辨率时按 16:9 占位，免得格子高度在拿到值那一刻跳一下 */
private const val DefaultAspectRatio = 16f / 9f

/**
 * 把一个 SurfaceView 的面交给**这一路自己的**特权进程
 *
 * 每格一块独立的面：`setMonitorSurface` 是 per-service 的，五路各推各的，
 * 与单路预览（[com.aliothmoon.maafw.runner.RemotePreviewPort]）互不干扰。
 * 拿不到 service 说明这一路还没连上，此时不递，等用户回设置页重连即可。
 */
/**
 * 解绑要用**不会随组合取消**的作用域
 *
 * `rememberCoroutineScope()` 挂在 composition 的 Job 上，这一屏被换掉的那一刻它就取消了，
 * 借它发起的解绑来不及起跑——而解绑一旦漏掉，那一格的面就一直挂在特权进程上，
 * 画面继续按帧渲染，电照耗。所以这里用进程级作用域，谁取消都不影响它把这件事做完。
 */
private val HandoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
private fun InstanceSurface(slot: Int) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val surface = holder.surface
                        HandoverScope.launch { handOver(slot, surface) }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        HandoverScope.launch { handOver(slot, null) }
                    }
                })
            }
        },
    )
    // 视图被换掉时 SurfaceHolder 的销毁回调不保证一定来，这里再兜一次
    DisposableEffect(slot) {
        onDispose { HandoverScope.launch { handOver(slot, null) } }
    }
}

/** binder 调用不能占着主线程：五路同时建面时这一下是很实在的耗时 */
private suspend fun handOver(slot: Int, surface: android.view.Surface?) {
    val service = RemoteServiceManager.slotConnectionOrNull(slot)?.serviceOrNull() ?: return
    withContext(Dispatchers.IO) {
        runCatching { service.setMonitorSurface(surface) }
    }
}
