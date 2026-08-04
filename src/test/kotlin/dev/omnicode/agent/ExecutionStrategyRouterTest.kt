package dev.omnicode.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionStrategyRouterTest {
    @Test
    fun `small edit stays single`() {
        assertEquals(
            AgentExecutionStrategy.SINGLE,
            ExecutionStrategyRouter.choose("简单改一处 typo", AgentMode.AGENT),
        )
    }

    @Test
    fun `research and cross module work use team`() {
        assertEquals(
            AgentExecutionStrategy.TEAM,
            ExecutionStrategyRouter.choose("做一份跨模块科研论文综述并定位 root cause", AgentMode.RESEARCH),
        )
    }

    @Test
    fun `many attachments are evidence heavy`() {
        assertEquals(
            AgentExecutionStrategy.TEAM,
            ExecutionStrategyRouter.choose("分析这些实验材料", AgentMode.AGENT, attachmentCount = 3),
        )
    }
}
