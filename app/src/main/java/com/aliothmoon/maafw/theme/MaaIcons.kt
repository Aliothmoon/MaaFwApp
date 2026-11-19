package com.aliothmoon.maafw.theme

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aliothmoon.maafw.semiicons.SemiIconRes
import com.aliothmoon.maafw.semiicons.SemiIcons

/**
 * 业务语义图标：DEFAULT 用 Material，SEMI_DESIGN 用 `:semi-icons` 模块里的 Semi 资源
 *
 * 全量索引：[SemiIconRes.Mono] / [SemiIconRes.Lab]（在 `:semi-icons`）
 */
object MaaIcons {

    val Home: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.home_stroked, Icons.Outlined.Home)

    val HomeFilled: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.home, Icons.Filled.Home)

    val Checklist: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.checklist_stroked, Icons.Outlined.Checklist)

    val ChecklistFilled: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.check_list, Icons.Filled.Checklist)

    val Schedule: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.clock_stroked, Icons.Outlined.Schedule)

    val ScheduleFilled: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.clock, Icons.Filled.Schedule)

    val Settings: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.setting_stroked, Icons.Outlined.Settings)

    val SettingsFilled: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.setting, Icons.Filled.Settings)

    val Close: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.close, Icons.Outlined.Close)

    val Add: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.plus, Icons.Outlined.Add)

    val Search: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.search, Icons.Outlined.Search)

    val Delete: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.delete_stroked, Icons.Outlined.DeleteOutline)

    val Edit: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.edit_stroked, Icons.Outlined.Edit)

    val Copy: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.copy_stroked, Icons.Outlined.ContentCopy)

    val Check: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.tick, Icons.Outlined.Check)

    val History: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.history, Icons.Outlined.History)

    val More: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.more, Icons.Outlined.MoreVert)

    val Refresh: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.refresh, Icons.Outlined.Refresh)

    val Lock: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.lock_stroked, Icons.Outlined.Lock)

    val Unlock: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.unlock_stroked, Icons.Outlined.LockOpen)

    val Link: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.link, Icons.Outlined.Link)

    val Unlink: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.unlink, Icons.Outlined.LinkOff)

    val Wrench: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.wrench_stroked, Icons.Outlined.Build)

    val Info: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.info_circle, Icons.Outlined.Info)

    val Skipped: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.minus_circle, Icons.Filled.DoNotDisturbOn)

    val Warning: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.alert_triangle, Icons.Outlined.Warning)

    val WarningAmber: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.alert_triangle, Icons.Outlined.WarningAmber)

    val Error: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.alert_circle, Icons.Outlined.ErrorOutline)

    val Play: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.play, Icons.Outlined.PlayArrow)

    val Layers: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.layers, Icons.Outlined.Layers)

    val Moon: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.moon_stroked, Icons.Outlined.Nightlight)

    val Handle: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.handle, Icons.Outlined.DragIndicator)

    val ChevronRight: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.chevron_right, Icons.Outlined.ChevronRight)

    val ChevronDown: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.chevron_down, Icons.Outlined.ExpandMore)

    val ChevronUp: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.chevron_up, Icons.Outlined.ExpandLess)

    val ChevronUpDown: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.chevron_up_down, Icons.Outlined.UnfoldMore)

    val ArrowBack: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.arrow_left, Icons.AutoMirrored.Outlined.ArrowBack)

    val ArrowBackFilled: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.arrow_left, Icons.AutoMirrored.Filled.ArrowBack)

    val CaretDown: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.caretdown, Icons.Outlined.ArrowDropDown)

    val Video: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.video_stroked, Icons.Outlined.OndemandVideo)

    val Power: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.quit, Icons.Outlined.PowerSettingsNew)

    val AddTask: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.checklist_stroked, Icons.Outlined.AddTask)

    val PlaylistAdd: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.list_view, Icons.AutoMirrored.Outlined.PlaylistAdd)

    val KeyboardArrowRight: ImageVector
        @Composable get() =
            themed(SemiIconRes.Mono.chevron_right, Icons.AutoMirrored.Filled.KeyboardArrowRight)

    /** 离开本应用的链接行尾标，与进二级页的 › 分开 */
    val ExternalLink: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.external_open, Icons.AutoMirrored.Outlined.OpenInNew)

    // ── 设置页行首图标：一行一个，按这一行管的事起名；Semi 那侧有 _stroked 的优先取它，与线框的 Material 同一观感 ──

    val Update: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.sync, Icons.Outlined.Update)

    val Download: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.download_stroked, Icons.Outlined.Download)

    val Contrast: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.contrast, Icons.Outlined.Contrast)

    val Palette: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.color_palette, Icons.Outlined.Palette)

    val FontSize: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.font, Icons.Outlined.FormatSize)

    val Wallpaper: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.image_stroked, Icons.Outlined.Wallpaper)

    val Language: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.language, Icons.Outlined.Translate)

    val Timer: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.stopwatch_stroked, Icons.Outlined.Timer)

    val Notification: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.bell_stroked, Icons.Outlined.Notifications)

    val Article: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.book_open_stroked, Icons.AutoMirrored.Outlined.Article)

    val Share: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.share_stroked, Icons.Outlined.IosShare)

    val Debug: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.wrench_stroked, Icons.Outlined.BugReport)

    val DebugImage: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.camera_stroked, Icons.Outlined.PhotoLibrary)

    val Export: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.export, Icons.Outlined.FileUpload)

    val Import: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.import, Icons.Outlined.FileDownload)

    val ShareCode: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.ticket_code_stroked, Icons.Outlined.ContentPaste)

    val Reinstall: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.refresh, Icons.Outlined.RestartAlt)

    val Privileged: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.shield_stroked, Icons.Outlined.AdminPanelSettings)

    val Resolution: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.monitor_stroked, Icons.Outlined.AspectRatio)

    val PictureInPicture: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.mini_player, Icons.Outlined.PictureInPictureAlt)

    val Telemetry: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.pulse, Icons.Outlined.Insights)

    val Announcement: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.horn_stroked, Icons.Outlined.Campaign)

    val Contact: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.comment_stroked, Icons.Outlined.Forum)

    val License: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.book_stroked, Icons.Outlined.Gavel)

    val Repository: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.code_stroked, Icons.Outlined.Code)

    val AppVersion: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.smartphone_stroked, Icons.Outlined.PhoneAndroid)

    val ProjectVersion: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.box, Icons.Outlined.Inventory2)

    val FrameworkVersion: ImageVector
        @Composable get() = themed(SemiIconRes.Mono.server_stroked, Icons.Outlined.Memory)

    @Composable
    fun mono(@DrawableRes resId: Int): ImageVector = SemiIcons.mono(resId)

    @Composable
    fun lab(@DrawableRes resId: Int): ImageVector = SemiIcons.lab(resId)

    @Composable
    private fun themed(@DrawableRes semiRes: Int, material: ImageVector): ImageVector =
        when (LocalThemeStyle.current) {
            ThemeStyle.SEMI_DESIGN -> SemiIcons.mono(semiRes)
            ThemeStyle.DEFAULT -> material
        }
}
