package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchTaskRecipeExporterTest {
    @Test
    fun `recipe is bounded and does not include binary or history fields`() {
        val recipe = BatchTaskRecipeExporter.markdown(
            BatchTaskRecipeInput(
                title = "批量修复",
                prompt = "请修复 API 超时",
                mode = AgentMode.AGENT,
                strategy = AgentExecutionStrategy.AUTO,
                requiredImageAttachments = 2,
            ),
        )

        assertTrue(recipe.contains("请修复 API 超时"))
        assertTrue(recipe.contains("重新添加图片附件"))
        assertFalse(recipe.contains("conversationHistory"))
        assertTrue(recipe.length <= BatchTaskRecipeExporter.MAX_RECIPE_CHARS)
    }
}
