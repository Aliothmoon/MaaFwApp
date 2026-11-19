package com.aliothmoon.maafw.ui.notification

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.notification.LiveStyleViewModel
import com.aliothmoon.maafw.notification.live.LiveBackend
import com.aliothmoon.maafw.theme.MaaDesignTokens
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 运行通知样式（二级页面，移植自 MaaMeow 的 LiveUpdateSettingsView）
 *
 * 按实际生效的展示方式列项：标准通知栏没有状态栏短文本，不列显示内容；
 * 颜色与图标只在 [com.aliothmoon.maafw.notification.live.LiveCapability.styleConfigurable] 时列
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveStyleScreen(
    onBack: () -> Unit,
    viewModel: LiveStyleViewModel = koinViewModel(),
) {
    val style by viewModel.style.collectAsStateWithLifecycle()
    val icon by viewModel.icon.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (!viewModel.importCustomIcon(uri)) {
                Toast.makeText(appContext, R.string.live_style_icon_pick_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.live_style_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val live = capability
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            if (live == null) return@LazyColumn
            val island = live.backend == LiveBackend.HYPER_ISLAND
            if (live.backend != LiveBackend.PLAIN) {
                item(key = "chip") {
                    LiveChipCard(style, island, onSelect = viewModel::setChipContent)
                }
            }
            if (!live.styleConfigurable) return@LazyColumn
            item(key = "color") {
                LiveColorCard(
                    style = style,
                    onSelectScheme = viewModel::setColorScheme,
                    onSelectCustom = viewModel::setCustomColor,
                )
            }
            item(key = "icon") {
                LiveIconCard(
                    style = style,
                    icon = icon,
                    island = island,
                    onSelect = viewModel::setTrackerIcon,
                    onPick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClear = viewModel::clearCustomIcon,
                )
            }
            item(key = "hint") {
                val hintRes = when (live.backend) {
                    LiveBackend.HYPER_ISLAND -> R.string.live_style_hint_island
                    LiveBackend.LIVE_UPDATE -> R.string.live_style_hint
                    LiveBackend.PLAIN -> R.string.live_style_hint_plain
                }
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MaaDesignTokens.Spacing.xs),
                )
            }
        }
    }
}
