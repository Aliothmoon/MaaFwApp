package com.aliothmoon.maafw.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaInfoRow

/** 首页：项目 / 当前配置 / 运行状态摘要 + Start/Stop。 */
@Composable
fun HomeScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MaaDesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
    ) {
        ProjectCard(state)
        ConfigurationCard(state)
        RunnerCard(state)
    }
}

@Composable
private fun ProjectCard(state: SessionUiState) {
    MaaCard(title = "项目") {
        when (val project = state.projectState) {
            is ProjectState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(MaaDesignTokens.Spacing.xs))
                Text("正在加载 ProjectInterface…", style = MaterialTheme.typography.bodyMedium)
            }

            is ProjectState.Error -> {
                Text(
                    text = "项目加载失败",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                project.diagnostics.take(5).forEach {
                    Text(
                        text = "${it.source}: ${it.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is ProjectState.Ready -> {
                MaaInfoRow("名称", project.definition.name)
                MaaInfoRow("任务", "${project.definition.tasks.size} 个")
                MaaInfoRow("预设模板", "${project.definition.templates.size} 个")
                MaaInfoRow("资源", state.environment?.resourceName ?: "无")
                val warnings = project.diagnostics.size + state.sessionDiagnostics.size
                if (warnings > 0) {
                    val errors = (project.diagnostics + state.sessionDiagnostics)
                        .count { it.severity == DiagnosticSeverity.Error }
                    Text(
                        text = "诊断：$warnings 条${if (errors > 0) "（$errors 条错误）" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurationCard(state: SessionUiState) {
    MaaCard(title = "当前配置") {
        val active = state.activeConfiguration
        if (active == null) {
            Text(
                text = "尚未选择配置，请前往任务页创建或选择",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MaaInfoRow("名称", active.name)
            MaaInfoRow("任务", "${active.tasks.size} 个")
            MaaInfoRow("已启用", "${active.enabledTaskCount} 个（有效 ${active.effectiveTaskCount} 个）")
        }
    }
}

@Composable
private fun RunnerCard(state: SessionUiState) {
    MaaCard(title = "运行状态") {
        val runner = state.runner
        val execution = runner.activeExecution
        MaaInfoRow("状态", phaseText(runner.phase))
        if (execution != null) {
            if (execution.totalTaskCount > 0) {
                val animatedProgress by animateFloatAsState(
                    targetValue = execution.completedTaskCount.toFloat() / execution.totalTaskCount,
                    animationSpec = MaaMotion.enter(),
                    label = "runnerProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MaaInfoRow("进度", "${execution.completedTaskCount} / ${execution.totalTaskCount}")
            execution.currentTaskName?.let { MaaInfoRow("当前任务", it) }
        }
        runner.latestResult?.let { result ->
            MaaInfoRow("最近结果", resultText(result))
            result.taskResults.filterNot { it.success }.forEach {
                Text(
                    text = "失败：${it.taskName}${it.message?.let { m -> "（$m）" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun phaseText(phase: RunnerPhase): String = when (phase) {
    is RunnerPhase.Unavailable -> "不可用：${phase.reason}"
    RunnerPhase.Idle -> "空闲"
    RunnerPhase.Preparing -> "准备中"
    RunnerPhase.Running -> "运行中"
    RunnerPhase.Stopping -> "停止中"
}

private fun resultText(result: ExecutionResult): String = when (result) {
    is ExecutionResult.Completed -> "全部完成（${result.taskResults.size} 个任务）"
    is ExecutionResult.CompletedWithFailures ->
        "完成，${result.taskResults.count { !it.success }} 个任务失败"

    is ExecutionResult.Cancelled -> "已取消"
    is ExecutionResult.Failed -> "失败：${result.reason}"
}
