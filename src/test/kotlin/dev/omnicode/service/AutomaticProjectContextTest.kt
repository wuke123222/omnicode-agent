package dev.omnicode.service

import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticProjectContextTest {
    @Test
    fun `bounded rules only report files whose content entered final context`() {
        val result = ProjectRulesResult(
            appliedRules = listOf(
                rule("AGENTS.md", "a".repeat(2_000)),
                rule("CLAUDE.md", "must-not-enter"),
            ),
            combinedText = "unused",
            issues = emptyList(),
            truncation = emptyTruncation(),
        )

        val bounded = result.boundedAutomaticContext(1_024)

        assertEquals(listOf("AGENTS.md"), bounded.rulePaths)
        assertTrue(bounded.text.contains("### AGENTS.md"))
        assertFalse(bounded.text.contains("CLAUDE.md"))
        assertTrue(bounded.text.length <= 1_024)
        assertTrue(bounded.truncated)
    }

    @Test
    fun `context budget protects first and current goals plus system and omission reserve`() {
        val first = ConversationMessage(MessageRole.USER, "a".repeat(60_000))
        val middle = ConversationMessage(MessageRole.ASSISTANT, "discardable".repeat(10_000))
        val current = ConversationMessage(MessageRole.USER, "b".repeat(60_000))

        val budget = automaticProjectContextCharacterBudget(
            priorMessages = listOf(first, middle),
            currentUserMessage = current,
            maxContextCharacters = 180_000,
            remainingInputTokens = 250_000,
            maximumAutomaticCharacters = 112 * 1024,
        )

        assertTrue(budget in 30_000..36_000, "budget was $budget")
        assertTrue(120_000 + budget + 24 * 1024 <= 180_000)
    }

    @Test
    fun `context budget also obeys remaining cumulative input tokens`() {
        val current = ConversationMessage(MessageRole.USER, "goal")

        val budget = automaticProjectContextCharacterBudget(
            priorMessages = emptyList(),
            currentUserMessage = current,
            maxContextCharacters = 180_000,
            remainingInputTokens = 7_000,
            maximumAutomaticCharacters = 112 * 1024,
        )

        assertTrue(budget in 3_000..4_000, "budget was $budget")
        assertEquals(
            0,
            automaticProjectContextCharacterBudget(
                priorMessages = emptyList(),
                currentUserMessage = current,
                maxContextCharacters = 10_000,
                remainingInputTokens = 250_000,
                maximumAutomaticCharacters = 112 * 1024,
            ),
        )
    }

    private fun rule(path: String, content: String) = AppliedProjectRule(
        relativePath = path,
        content = content,
        totalBytes = content.length.toLong(),
        includedBytes = content.length,
        includedCharacters = content.length,
        truncated = false,
    )

    private fun emptyTruncation() = ProjectRuleTruncationStats(
        discoveredFiles = 0,
        appliedFiles = 0,
        ignoredFiles = 0,
        rejectedFiles = 0,
        truncatedFiles = 0,
        discoveryTruncated = false,
        totalSourceBytes = 0,
        includedBytes = 0,
        includedCharacters = 0,
        omittedBytes = 0,
        omittedKnownCharacters = 0,
    )
}
