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
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
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
    MaaCard(title = stringResource(R.string.home_project)) {
        when (val project = state.projectState) {
            is ProjectState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(MaaDesignTokens.Spacing.xs))
                Text(stringResource(R.string.home_project_loading), style = MaterialTheme.typography.bodyMedium)
            }

            is ProjectState.Error -> {
                Text(
                    text = stringResource(R.string.home_project_load_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                MaaDiagnosticList(project.diagnostics.take(5))
            }

            is ProjectState.Ready -> {
                MaaInfoRow(stringResource(R.string.home_name), project.definition.name)
                MaaInfoRow(
                    stringResource(R.string.home_tasks),
                    stringResource(R.string.home_count_items, project.definition.tasks.size),
                )
                MaaInfoRow(
                    stringResource(R.string.home_templates),
                    stringResource(R.string.home_count_items, project.definition.templates.size),
                )
                MaaInfoRow(
                    stringResource(R.string.home_resource),
                    state.environment?.resourceName ?: stringResource(R.string.home_none),
                )
                val diagnostics = state.visibleDiagnostics
                if (diagnostics.isNotEmpty()) {
                    val errors = diagnostics.count { it.severity == DiagnosticSeverity.Error }
                    Text(
                        text = if (errors > 0) {
                            stringResource(R.string.home_diagnostics_summary_with_errors, diagnostics.size, errors)
                        } else {
                            stringResource(R.string.home_diagnostics_summary, diagnostics.size)
                        },
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
    MaaCard(title = stringResource(R.string.home_current_config)) {
        val active = state.activeConfiguration
        if (active == null) {
            Text(
                text = stringResource(R.string.home_no_config_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MaaInfoRow(stringResource(R.string.home_name), active.name)
            MaaInfoRow(
                stringResource(R.string.home_tasks),
                stringResource(R.string.home_count_items, active.tasks.size),
            )
            MaaInfoRow(
                stringResource(R.string.home_enabled),
                stringResource(R.string.home_enabled_count, active.enabledTaskCount, active.effectiveTaskCount),
            )
        }
    }
}

@Composable
private fun RunnerCard(state: SessionUiState) {
    MaaCard(title = stringResource(R.string.home_runner_status)) {
        val runner = state.runner
        val execution = runner.activeExecution
        MaaInfoRow(stringResource(R.string.home_state), phaseText(runner.phase))
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
            MaaInfoRow(
                stringResource(R.string.home_progress),
                "${execution.completedTaskCount} / ${execution.totalTaskCount}",
            )
            execution.currentTaskName?.let { MaaInfoRow(stringResource(R.string.home_current_task), it) }
        }
        runner.latestResult?.let { result ->
            MaaInfoRow(stringResource(R.string.home_latest_result), resultText(result))
            result.taskResults.filterNot { it.success }.forEach { failure ->
                Text(
                    text = failure.message
                        ?.let { stringResource(R.string.home_task_failed_with_message, failure.taskName, it) }
                        ?: stringResource(R.string.home_task_failed, failure.taskName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun phaseText(phase: RunnerPhase): String = when (phase) {
    is RunnerPhase.Unavailable -> stringResource(R.string.phase_unavailable, phase.reason)
    RunnerPhase.Idle -> stringResource(R.string.phase_idle)
    RunnerPhase.Preparing -> stringResource(R.string.phase_preparing)
    RunnerPhase.Running -> stringResource(R.string.phase_running)
    RunnerPhase.Stopping -> stringResource(R.string.phase_stopping)
}

@Composable
private fun resultText(result: ExecutionResult): String = when (result) {
    is ExecutionResult.Completed -> stringResource(R.string.result_completed, result.taskResults.size)
    is ExecutionResult.CompletedWithFailures ->
        stringResource(R.string.result_completed_with_failures, result.taskResults.count { !it.success })

    is ExecutionResult.Cancelled -> stringResource(R.string.result_cancelled)
    is ExecutionResult.Failed -> stringResource(R.string.result_failed, result.reason)
}
