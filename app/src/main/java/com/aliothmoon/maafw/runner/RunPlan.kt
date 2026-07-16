package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.serialization.json.JsonObject

/**
 * Start 时捕获的不可变执行输入。
 * Runner 开始后只消费该计划，不继续观察用户配置。
 */
data class RunPlan(
    val projectName: String,
    val projectVersion: String?,
    val controller: ControllerDefinition,
    val resource: ResourceDefinition,
    val runConfigurationId: RunConfigurationId,
    val tasks: List<RuntimeTask>,
)

/** ConfiguredTask 经过 applicability 过滤和 option 编译后的单次执行表示。 */
data class RuntimeTask(
    val taskName: String,
    val entry: String,
    /** 保持有序 patch 列表，Runner 按顺序传给 MaaFramework，不得提前合并。 */
    val pipelineOverrides: List<JsonObject>,
)
