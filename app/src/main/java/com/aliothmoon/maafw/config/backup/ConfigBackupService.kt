package com.aliothmoon.maafw.config.backup

import android.content.Context
import android.net.Uri
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.schedule.ScheduleAlarmManager
import com.aliothmoon.maafw.schedule.ScheduleStrategyStore
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.util.displayName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ConfigReadResult {
    data class Ready(val import: ConfigImport) : ConfigReadResult
    data class Failed(val error: ConfigImportError) : ConfigReadResult

    /** 文件读不出来（权限没了、太大） */
    data object Unreadable : ConfigReadResult
    data object ProjectNotReady : ConfigReadResult
}

enum class ConfigApplyResult { Applied, Locked, ProjectNotReady, Failed }

/**
 * 配置的导出与导入：读写用户经 SAF 选的文件，确认后写进各存储
 *
 * 运行配置平时只由 SessionViewModel 写；这里绕开它是因为导入要同时改配置、定时与设置三处，
 * 而 [UserConfigurationStore.update] 本身是原子的，不会和那边抢出半截状态。
 * 运行中不许导入，与 SessionViewModel 的 guarded 是同一条锁
 */
class ConfigBackupService(
    private val context: Context,
    private val configurationStore: UserConfigurationStore,
    private val scheduleStore: ScheduleStrategyStore,
    private val alarms: ScheduleAlarmManager,
    private val appSettings: AppSettingsManager,
    private val projectRepository: ProjectRepository,
    private val runnerPort: RunnerPort,
    /** 等定时读盘的上限；单测里调短 */
    private val storeReadyTimeoutMs: Long = STORE_READY_TIMEOUT_MS,
) {

    fun suggestedFileName(): String {
        val project = definition()?.name ?: "maafw"
        return "${project}-config-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    }

    /** 成功返回文件的显示名 */
    suspend fun exportTo(target: Uri): String? = withContext(MaaDispatchers.IO) {
        val definition = definition() ?: return@withContext null
        // 定时还没读出来就导，文件里是空列表，日后拿它恢复会把定时全清掉
        if (!awaitSchedulesLoaded()) return@withContext null
        runCatching {
            val backup = ConfigBackupCodec.export(
                config = configurationStore.data.first(),
                definition = definition,
                schedules = scheduleStore.strategies.value,
                appSettings = appSettings.portableSettings(),
                app = BackupAppInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME),
                exportedAt = System.currentTimeMillis(),
            )
            val text = ConfigBackupCodec.encode(backup)
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.encodeToByteArray()) }
                ?: return@runCatching null
            context.contentResolver.displayName(target) ?: suggestedFileName()
        }.onFailure { Timber.w(it, "Failed to export configuration to %s", target) }.getOrNull()
    }

    suspend fun read(source: Uri): ConfigReadResult = withContext(MaaDispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    check(out.size() <= MAX_FILE_BYTES) { "configuration file too large" }
                }
                out.toByteArray().decodeToString()
            }
        }.onFailure { Timber.w(it, "Failed to read configuration from %s", source) }.getOrNull()
            ?: return@withContext ConfigReadResult.Unreadable
        parse(text, context.contentResolver.displayName(source))
    }

    /** 粘贴进来的 MXU 分享码 */
    suspend fun parseText(text: String): ConfigReadResult = withContext(MaaDispatchers.IO) { parse(text, null) }

    private suspend fun parse(text: String, fileName: String?): ConfigReadResult {
        val definition = definition() ?: return ConfigReadResult.ProjectNotReady
        return when (val parsed = ConfigImportParser.parse(text, fileName, definition, configurationStore.data.first())) {
            is ConfigImportParse.Parsed -> ConfigReadResult.Ready(parsed.import)
            is ConfigImportParse.Failed -> ConfigReadResult.Failed(parsed.error)
        }
    }

    /**
     * 写配置、定时、设置是三笔，不随调用方的页面一起取消：写到一半停下，
     * 留下的定时会指着已经换掉的配置
     */
    suspend fun apply(import: ConfigImport): ConfigApplyResult = withContext(MaaDispatchers.IO + NonCancellable) {
        if (runnerPort.state.value.phase.isBusy) return@withContext ConfigApplyResult.Locked
        val definition = definition() ?: return@withContext ConfigApplyResult.ProjectNotReady
        runCatching {
            when (import) {
                is ConfigImport.Restore -> restore(import.backup, definition)
                is ConfigImport.FromMxu -> {
                    configurationStore.update { import.result.applyTo(it, definition) }
                    // 导进来的定时都关着，不用挂闹钟
                    scheduleStore.addAll(import.result.schedules)
                }
            }
            ConfigApplyResult.Applied
        }.onFailure { Timber.e(it, "Failed to import configuration") }.getOrDefault(ConfigApplyResult.Failed)
    }

    private suspend fun restore(backup: ConfigBackup, definition: ProjectDefinition) {
        // 读不出现有的定时就认不出哪些规则被删了、闹钟该撤哪些，宁可这次不导
        check(awaitSchedulesLoaded()) { "schedule rules not loaded" }
        val restored = configurationStore.update { ConfigBackupCodec.restore(backup, it, definition) }
        val schedules = ConfigBackupCodec.restoreSchedules(backup, restored.configurations.mapTo(HashSet()) { it.id })
        val kept = schedules.mapTo(HashSet()) { it.id }
        // 账本只记投递过、在重试的规则，没响过的闹钟 rescheduleAll 认不出来，得在这里撤
        scheduleStore.strategies.value.map { it.id }.filterNot { it in kept }.forEach(alarms::forget)
        scheduleStore.replaceAll(schedules)
        alarms.rescheduleAll(schedules)
        appSettings.importPortableSettings(backup.appSettings)
    }

    private suspend fun awaitSchedulesLoaded(): Boolean =
        withTimeoutOrNull(storeReadyTimeoutMs) { scheduleStore.isLoaded.first { it } } != null

    private fun definition(): ProjectDefinition? =
        (projectRepository.state.value as? ProjectState.Ready)?.definition

    private companion object {
        /** MXU 的整份配置几十 KB；放宽到 8MB，再大就不是配置文件 */
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val STORE_READY_TIMEOUT_MS = 5_000L
    }
}
