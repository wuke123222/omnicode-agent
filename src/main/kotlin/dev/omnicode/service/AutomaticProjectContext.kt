package dev.omnicode.service

import dev.omnicode.agent.ContextSelector
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.provider.ReasoningEffort

internal data class BoundedProjectRulesContext(
    val text: String,
    val rulePaths: List<String>,
    val truncated: Boolean,
)

/** Renders rule files atomically enough that every reported path has actual rule text in context. */
internal fun ProjectRulesResult.boundedAutomaticContext(maxCharacters: Int): BoundedProjectRulesContext {
    if (maxCharacters <= 0 || appliedRules.isEmpty()) {
        return BoundedProjectRulesContext("", emptyList(), appliedRules.isNotEmpty())
    }
    val builder = StringBuilder(PROJECT_RULES_PREAMBLE)
    val includedPaths = mutableListOf<String>()
    var clipped = false
    for (rule in appliedRules) {
        val heading = "\n\n### ${rule.relativePath}\n"
        val remaining = maxCharacters - builder.length - PROJECT_RULES_FOOTER.length - heading.length
        if (remaining <= 0) {
            clipped = true
            break
        }
        val content = safeCharacterPrefix(rule.content, remaining)
        if (content.isEmpty()) {
            if (rule.content.isNotEmpty()) clipped = true
            continue
        }
        builder.append(heading).append(content)
        includedPaths += rule.relativePath
        if (content.length < rule.content.length) {
            clipped = true
            break
        }
    }
    if (includedPaths.isEmpty()) {
        return BoundedProjectRulesContext("", emptyList(), appliedRules.isNotEmpty())
    }
    builder.append(PROJECT_RULES_FOOTER)
    return BoundedProjectRulesContext(
        text = builder.toString(),
        rulePaths = includedPaths,
        truncated = clipped || includedPaths.size < appliedRules.size,
    )
}

/**
 * Leaves deterministic room for the mode system prompt, omission summary, and framing overhead
 * before adding repository-authored data. The first and current user goals are both protected by
 * [ContextSelector], so both are subtracted from the available context window.
 */
internal fun automaticProjectContextCharacterBudget(
    priorMessages: List<ConversationMessage>,
    currentUserMessage: ConversationMessage,
    maxContextCharacters: Int,
    remainingInputTokens: Long,
    maximumAutomaticCharacters: Int,
): Int {
    if (maxContextCharacters <= 0 || remainingInputTokens <= 0 || maximumAutomaticCharacters <= 0) return 0
    val firstGoal = priorMessages.firstOrNull(::isActualUserGoal)
    val protectedMessages = listOfNotNull(firstGoal, currentUserMessage)
    val protectedCharacters = ContextSelector.estimatedInputTokens(protectedMessages)
        .coerceAtMost(Int.MAX_VALUE.toLong() / ESTIMATED_CHARACTERS_PER_TOKEN)
        .times(ESTIMATED_CHARACTERS_PER_TOKEN)
    val fixedReserve = SYSTEM_PROMPT_RESERVE_CHARACTERS.toLong() +
        OMISSION_AND_FRAMING_RESERVE_CHARACTERS
    val contextAvailable = maxContextCharacters.toLong() - protectedCharacters - fixedReserve
    val inputCharacterCapacity = remainingInputTokens
        .coerceAtMost(Long.MAX_VALUE / ESTIMATED_CHARACTERS_PER_TOKEN)
        .times(ESTIMATED_CHARACTERS_PER_TOKEN)
    val inputAvailable = inputCharacterCapacity - protectedCharacters - fixedReserve
    return minOf(maximumAutomaticCharacters.toLong(), contextAvailable, inputAvailable)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
}

/**
 * Keeps the first provider request latency-first without disabling automatic context entirely.
 * A user can still pin or attach the exact files they need; subsequent turns use the regular
 * context budget once the background repository snapshot is warm.
 */
internal fun firstRequestAutomaticContextCharacterLimit(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    -> 24 * 1024
    ReasoningEffort.AUTO,
    ReasoningEffort.NONE,
    ReasoningEffort.MEDIUM,
    -> 48 * 1024
    ReasoningEffort.HIGH -> 72 * 1024
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
    -> 96 * 1024
}

private fun isActualUserGoal(message: ConversationMessage): Boolean =
    message.role == MessageRole.USER && message.blocks.any { it !is ContentBlock.ToolResult }

private const val ESTIMATED_CHARACTERS_PER_TOKEN = 4L
private const val SYSTEM_PROMPT_RESERVE_CHARACTERS = 16 * 1024
private const val OMISSION_AND_FRAMING_RESERVE_CHARACTERS = 8 * 1024
