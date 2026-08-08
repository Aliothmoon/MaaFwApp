package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.serialization.json.JsonObject

/** Start 时冻结的执行输入；Runner 不再观察用户配置 */
data class RunPlan(
    val projectName: String,
    val projectVersion: String?,
    val controller: ControllerDefinition,
    val resource: ResourceDefinition,
    val runConfigurationId: RunConfigurationId,
    val tasks: List<RuntimeTask>,
)

data class RuntimeTask(
    val taskName: String,
    val entry: String,
    /** 有序 patch；Runner 按序传给 MaaFramework，不得提前合并 */
    val pipelineOverrides: List<JsonObject>,
)
