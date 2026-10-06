package com.aliothmoon.maafw.ui.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSwitchRow
import com.aliothmoon.maafw.ui.components.MaaValueSlider
import com.aliothmoon.maafw.ui.settings.search.SettingSearchTarget
import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.wallpaper.WallpaperSettings
import com.aliothmoon.maafw.wallpaper.WallpaperViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 预览图高度：只看构图和明暗，不必铺满 */
private val PreviewHeight = 140.dp

/** 自定义背景二级页：开关、选图裁剪、不透明度 / 遮罩 / 模糊（移植自 MaaMeow 的 WallpaperSettingsView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(
    onBack: () -> Unit,
    viewModel: WallpaperViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val image by viewModel.image.collectAsStateWithLifecycle()

    val crop = rememberWallpaperCropController(viewModel)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(crop::pick)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.wallpaper_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            item(key = "wallpaper") {
                WallpaperCard(
                    settings = settings,
                    image = image,
                    onEnabledChange = viewModel::setEnabled,
                    onPick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemove = viewModel::remove,
                    onImageAlphaChange = viewModel::setImageAlpha,
                    onScrimChange = viewModel::setScrim,
                    onBlurChange = viewModel::setBlur,
                )
            }
        }
    }

    // 选图后先进全屏裁剪；确认才落盘，取消则清掉缓存里的源图
    val source = crop.sourceBitmap
    if (crop.sourcePath != null && source != null) {
        WallpaperCropFullScreen(
            sourceBitmap = source,
            cropState = crop.cropState,
            onCancel = crop::cancel,
            onConfirm = crop::confirm,
        )
    }
}

@Composable
private fun WallpaperCard(
    settings: WallpaperSettings,
    image: ImageBitmap?,
    onEnabledChange: (Boolean) -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onImageAlphaChange: (Int) -> Unit,
    onScrimChange: (Int) -> Unit,
    onBlurChange: (Int) -> Unit,
) {
    MaaCard {
        SettingSearchTarget(SettingAnchors.WALLPAPER_ENABLE) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
                MaaSwitchRow(
                    label = stringResource(R.string.wallpaper_enable),
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                )
                Text(
                    text = stringResource(R.string.wallpaper_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = settings.enabled) {
            Column(
                modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PreviewHeight)
                            .clip(MaterialTheme.shapes.medium),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    MaaButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (image != null) R.string.wallpaper_replace else R.string.wallpaper_pick))
                    }
                    if (image != null) {
                        MaaOutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.wallpaper_remove))
                        }
                    }
                }
                if (image != null) {
                    MaaValueSlider(
                        label = stringResource(R.string.wallpaper_image_alpha),
                        value = settings.imageAlpha,
                        range = 0..100,
                        onValueCommit = onImageAlphaChange,
                    )
                    MaaValueSlider(
                        label = stringResource(R.string.wallpaper_scrim),
                        value = settings.scrim,
                        range = 0..100,
                        onValueCommit = onScrimChange,
                    )
                    // RenderEffect 模糊只有 API 31+ 有，低版本拖了也看不出变化，干脆不给
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MaaValueSlider(
                            label = stringResource(R.string.wallpaper_blur),
                            value = settings.blur,
                            range = 0..100,
                            onValueCommit = onBlurChange,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一次选图裁剪会话（移植自 MaaMeow 的 BackgroundCropController）
 *
 * 源图路径存在 rememberSaveable 里：进程被杀重建后图片选择器的 Uri 授权通常已失效，
 * 只能从缓存文件重新解码
 */
@Stable
private class WallpaperCropController(
    private val viewModel: WallpaperViewModel,
    private val scope: CoroutineScope,
    private val appContext: Context,
    val cropState: WallpaperCropState,
    private val sourcePathState: MutableState<String?>,
) {
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
        private set

    val sourcePath: String? get() = sourcePathState.value

    fun pick(uri: Uri) {
        scope.launch {
            val path = viewModel.prepareSource(uri)
            val bitmap = path?.let { viewModel.decodeSource(it) }
            if (path != null && bitmap != null) {
                cropState.initialized = false
                sourcePathState.value = path
                sourceBitmap = bitmap
            } else {
                viewModel.discardSource()
                showFailure()
            }
        }
    }

    suspend fun restoreIfNeeded() {
        val path = sourcePathState.value ?: return
        if (sourceBitmap != null) return
        val bitmap = viewModel.decodeSource(path)
        if (bitmap != null) {
            sourceBitmap = bitmap
        } else {
            sourcePathState.value = null
            viewModel.discardSource()
            showFailure()
        }
    }

    fun cancel() = endSession()

    /** 存成功才结束会话；失败提示后留在裁剪页，用户可以再点一次 */
    suspend fun confirm(cropped: Bitmap) {
        try {
            if (viewModel.saveCropped(cropped)) endSession() else showFailure()
        } finally {
            cropped.recycle()
        }
    }

    private fun endSession() {
        sourceBitmap = null
        sourcePathState.value = null
        viewModel.discardSource()
    }

    private fun showFailure() {
        Toast.makeText(appContext, R.string.wallpaper_import_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun rememberWallpaperCropController(viewModel: WallpaperViewModel): WallpaperCropController {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sourcePathState = rememberSaveable { mutableStateOf<String?>(null) }
    val cropState = rememberSaveable(saver = WallpaperCropState.Saver) { WallpaperCropState() }
    val controller = remember {
        WallpaperCropController(viewModel, scope, appContext, cropState, sourcePathState)
    }
    LaunchedEffect(sourcePathState.value) { controller.restoreIfNeeded() }
    return controller
}
