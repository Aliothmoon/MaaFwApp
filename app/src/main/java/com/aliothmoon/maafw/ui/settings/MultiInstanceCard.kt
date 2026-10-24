package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.privileged.RemoteServiceManager
import com.aliothmoon.maafw.privileged.ShizukuProcessServiceConnector
import com.aliothmoon.maafw.project.SlotWorkspace
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.tasks.instanceOrdinal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 多开（实验）
 *
 * 每一路是一个**独立的后台服务进程**（`pkg:shizuku_service_N`），各自持有自己的 binder、
 * 虚拟屏与 MaaFramework 句柄，因此互不干扰。0 号路是主流程本来就在用的那一路，
 * 这里的按钮只负责再拉起 / 释放额外的路。
 *
 * 当前版本仅打通「多路服务进程并存」这一层，用来在真机上确认多开成立；
 * 每路的建屏与任务下发随后接到任务工作区。
 */
@Composable
fun MultiInstanceCard(modifier: Modifier = Modifier) {
    // 删实例时要顺手把这一路自己的配置清掉，见下面「删除实例」
    val configurationStore: UserConfigurationStore = koinInject()
    var states by remember { mutableStateOf<Map<Int, PrivilegedServiceState>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun snapshot() {
        states = RemoteServiceManager.activeSlots.associateWith { slot ->
            RemoteServiceManager.connection(slot).serviceState.value
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot()
            delay(800)
        }
    }

    MaaCard(modifier = modifier, title = "多开（实验）") {
        Text(
            "每一个实例是一个独立的后台服务进程，各自持有自己的虚拟屏与任务，互不干扰；"
                    + "实例一是主流程正在用的那一个。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))

        states.entries.sortedBy { it.key }.forEach { (slot, state) ->
            val primary = slot == ShizukuProcessServiceConnector.PRIMARY_SLOT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (primary) {
                        "实例${instanceOrdinal(slot + 1)}（主）"
                    } else {
                        "实例${instanceOrdinal(slot + 1)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stateLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state) {
                        PrivilegedServiceState.Connected -> MaterialTheme.colorScheme.primary
                        PrivilegedServiceState.Died,
                        PrivilegedServiceState.Error -> MaterialTheme.colorScheme.error

                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaaButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val slot = RemoteServiceManager.allocateSlot()
                        snapshot()
                        message = "正在拉起实例${instanceOrdinal(slot + 1)}…"
                        runCatching { RemoteServiceManager.connection(slot).getInstance() }
                            .onSuccess { message = "实例${instanceOrdinal(slot + 1)}已连上" }
                            .onFailure { message = "实例${instanceOrdinal(slot + 1)}失败：${it.message}" }
                        busy = false
                        snapshot()
                    }
                },
            ) { Text("新建实例") }

            MaaButton(
                enabled = !busy && states.keys.any { it != ShizukuProcessServiceConnector.PRIMARY_SLOT },
                onClick = {
                    val target = states.keys
                        .filter { it != ShizukuProcessServiceConnector.PRIMARY_SLOT }
                        .maxOrNull()
                    if (target != null) {
                        RemoteServiceManager.releaseSlot(target)
                        // 号位会被下一个新实例复用：把这一路自己的配置与工作目录一并清掉，
                        // 否则新建出来的实例会带着上一任的任务勾选、参数与运行环境
                        scope.launch { runCatching { configurationStore.forgetSlot(target) } }
                        SlotWorkspace.erase(target)
                        message = "已删除实例${instanceOrdinal(target + 1)}"
                        snapshot()
                    }
                },
            ) { Text("删除实例") }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun stateLabel(state: PrivilegedServiceState): String = when (state) {
    PrivilegedServiceState.Connected -> "已连接"
    PrivilegedServiceState.Connecting -> "连接中…"
    PrivilegedServiceState.Disconnected -> "未连接"
    PrivilegedServiceState.Died -> "已断开"
    PrivilegedServiceState.Error -> "失败"
}
