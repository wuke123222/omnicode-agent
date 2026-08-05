package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.persistence.DefaultSensitiveDataRedactor

/** A portable, bounded recipe that describes a task without carrying its conversation history. */
data class BatchTaskRecipeInput(
    val title: String,
    val prompt: String,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
    val requiredImageAttachments: Int,
)

object BatchTaskRecipeExporter {
    const val MAX_RECIPE_CHARS = 24_000
    private const val MAX_PROMPT_CHARS = 12_000
    private val redactor = DefaultSensitiveDataRedactor()

    fun markdown(input: BatchTaskRecipeInput): String {
        val recipe = buildString {
            appendLine("# OmniCode 批量任务配方")
            appendLine()
            appendLine("- 名称：`${cell(input.title, 180)}`")
            appendLine("- 模式：`${input.mode.name}`")
            appendLine("- 执行策略：`${input.strategy.name}`")
            appendLine("- 需要重新选择的图片：${input.requiredImageAttachments}")
            appendLine()
            appendLine("## 任务目标")
            appendLine()
            appendLine(cell(input.prompt, MAX_PROMPT_CHARS))
            appendLine()
            appendLine("## 使用说明")
            appendLine()
            appendLine("1. 在目标项目的 OmniCode 输入框中粘贴“任务目标”。")
            appendLine("2. 重新确认模式、审批、沙箱和模型；不要把本配方视为授权。")
            if (input.requiredImageAttachments > 0) {
                appendLine("3. 按提示重新添加图片附件；配方不包含二进制附件。")
            }
            appendLine()
            appendLine("> 配方只保存有界的用户目标和运行偏好，不包含历史消息、工具输出、API Key 或仓库快照。")
        }
        return recipe.take(MAX_RECIPE_CHARS)
    }

    private fun cell(value: String, maxChars: Int): String = redactor.redact(value)
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("`", "'")
        .trim()
        .take(maxChars)
}
