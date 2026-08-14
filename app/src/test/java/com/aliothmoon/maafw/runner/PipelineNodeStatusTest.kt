package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.maa.MaaMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineNodeStatusTest {

    @Test
    fun `Starting uses the top-level name`() {
        assertEquals(
            "StartFight",
            PipelineNodeStatus.nameOf(
                MaaMsg.NODE_PIPELINE_NODE_STARTING,
                """{"task_id":1,"node_id":2,"name":"StartFight"}""",
            ),
        )
    }

    @Test
    fun `Succeeded prefers the hit node`() {
        assertEquals(
            "ClickStart",
            PipelineNodeStatus.nameOf(
                MaaMsg.NODE_PIPELINE_NODE_SUCCEEDED,
                """{"name":"StartFight","node_details":{"name":"ClickStart","completed":true}}""",
            ),
        )
    }

    @Test
    fun `Succeeded without node_details falls back to name`() {
        assertEquals(
            "StartFight",
            PipelineNodeStatus.nameOf(MaaMsg.NODE_PIPELINE_NODE_SUCCEEDED, """{"name":"StartFight"}"""),
        )
    }

    @Test
    fun `recognition callbacks are ignored`() {
        assertNull(
            PipelineNodeStatus.nameOf(MaaMsg.NODE_ACTION_STARTING, """{"name":"Click"}"""),
        )
    }

    @Test
    fun `focus wins over the pipeline node`() {
        assertEquals(
            "刷到第3关",
            resolveLiveUpdateStatus(focus = "刷到第3关", pipelineNode = "StartFight", fallback = "任务开始"),
        )
    }

    @Test
    fun `pipeline node wins over the fallback log`() {
        assertEquals(
            "StartFight",
            resolveLiveUpdateStatus(focus = null, pipelineNode = "StartFight", fallback = "任务开始"),
        )
    }

    @Test
    fun `blank focus does not block the node name`() {
        assertEquals(
            "StartFight",
            resolveLiveUpdateStatus(focus = "  \n", pipelineNode = "StartFight", fallback = "任务开始"),
        )
    }
}
