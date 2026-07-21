package dev.omnicode.agent

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole

object ContextSelector {
    fun select(messages: List<ConversationMessage>, maxChars: Int): List<ConversationMessage> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (messages.sumOf(::sizeOf) <= maxChars.toLong()) return messages

        val groups = groupToolExchanges(messages)
        val system = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val firstGoal = messages.firstOrNull(::isUserRequest)
        // Tool observations use the USER role for provider compatibility. Preserve the latest
        // actual user-authored request separately so a trailing tool result cannot displace the
        // current run's goal, acceptance criteria, or constraints under context pressure.
        val currentGoal = messages.lastOrNull(::isUserRequest)
        val latest = messages.lastOrNull()
        val requiredMessages = listOfNotNull(system, firstGoal, currentGoal, latest)
        val selectedGroupIndexes = groups.indices.filterTo(linkedSetOf()) { index ->
            groups[index].any { message -> requiredMessages.any { required -> message === required } }
        }
        val omission = ConversationMessage(MessageRole.SYSTEM, compressedExecutionMemory(messages))
        val requiredSize = selectedGroupIndexes.sumOf { index -> groups[index].sumOf(::sizeOf) }
        val minimumSize = requiredSize + sizeOf(omission)
        if (minimumSize > maxChars.toLong()) {
            throw ContextBudgetExceededException(
                "The system instructions, user goals, and latest message exceed the context budget.",
            )
        }

        var remaining = maxChars.toLong() - minimumSize
        for (index in groups.indices.reversed()) {
            if (index in selectedGroupIndexes) continue
            val size = groups[index].sumOf(::sizeOf)
            if (size > remaining) continue
            selectedGroupIndexes += index
            remaining -= size
        }

        val sortedIndexes = selectedGroupIndexes.sorted()
        var previousIndex: Int? = null
        var omissionAdded = false
        return buildList {
            sortedIndexes.forEach { index ->
                if (!omissionAdded && previousIndex != null && index > requireNotNull(previousIndex) + 1) {
                    add(omission)
                    omissionAdded = true
                }
                addAll(groups[index])
                previousIndex = index
            }
            if (!omissionAdded) add(omission)
        }
    }

    fun estimatedInputTokens(messages: List<ConversationMessage>): Long =
        (messages.sumOf(::sizeOf) + ESTIMATED_CHARS_PER_TOKEN - 1) / ESTIMATED_CHARS_PER_TOKEN

    private fun groupToolExchanges(messages: List<ConversationMessage>): List<List<ConversationMessage>> {
        val groups = mutableListOf<List<ConversationMessage>>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            val callIds = current.blocks.filterIsInstance<ContentBlock.ToolCall>().mapTo(mutableSetOf()) { it.id }
            val next = messages.getOrNull(index + 1)
            val resultIds = next?.blocks?.filterIsInstance<ContentBlock.ToolResult>()?.mapTo(mutableSetOf()) {
                it.toolCallId
            }.orEmpty()
            if (callIds.isNotEmpty() && resultIds.isNotEmpty() && resultIds.all(callIds::contains)) {
                groups += listOf(current, requireNotNull(next))
                index += 2
            } else {
                groups += listOf(current)
                index++
            }
        }
        return groups
    }

    private fun sizeOf(message: ConversationMessage): Long = message.blocks.sumOf {
        when (it) {
            is ContentBlock.Text -> it.text.length.toLong()
            is ContentBlock.TransientProjectContext -> it.text.length.toLong()
            // Image payloads are binary transport data, not text tokens. Reserve a conservative
            // fixed visual-context allowance so an image-only task cannot bypass the run budget.
            is ContentBlock.Image -> 16_000L
            is ContentBlock.ToolCall -> it.name.length.toLong() + it.arguments.toString().length + 64
            is ContentBlock.ToolResult -> it.content.length.toLong() + 64
        }
    } + 32

    private fun isUserRequest(message: ConversationMessage): Boolean =
        message.role == MessageRole.USER && message.blocks.any { it !is ContentBlock.ToolResult }

    private const val ESTIMATED_CHARS_PER_TOKEN = 4L
}

/**
 * Keeps only structural execution facts from trimmed turns. Raw tool output is deliberately
 * excluded because it is untrusted project data and must never be promoted into a system message.
 */
internal fun compressedExecutionMemory(messages: List<ConversationMessage>): String {
    val results = messages.asSequence()
        .flatMap { it.blocks.asSequence() }
        .filterIsInstance<ContentBlock.ToolResult>()
        .associateBy(ContentBlock.ToolResult::toolCallId)
    val facts = messages.asSequence()
        .flatMap { it.blocks.asSequence() }
        .filterIsInstance<ContentBlock.ToolCall>()
        .mapNotNull { call ->
            val result = results[call.id] ?: return@mapNotNull null
            val outcome = if (result.isError) "failed" else "completed"
            when (call.name) {
                "apply_patch", "apply_change" -> {
                    val path = safeMemoryValue(call.arguments.get("path")?.runCatching { asString }?.getOrNull())
                    "${call.name} $outcome${path?.let { " for $it" }.orEmpty()}"
                }
                "run_command" -> {
                    val executable = call.arguments.get("argv")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.firstOrNull()
                        ?.runCatching { asString }
                        ?.getOrNull()
                        ?.let(::safeMemoryValue)
                    "run_command $outcome${executable?.let { " ($it)" }.orEmpty()}"
                }
                else -> if (result.isError) "${safeMemoryValue(call.name) ?: "tool"} failed" else null
            }
        }
        .toList()
        .takeLast(MAX_EXECUTION_MEMORY_FACTS)
    return buildString {
        append("Earlier middle conversation content was omitted to stay within the context budget. ")
        append("Re-read project files before editing because the workspace remains the source of truth.")
        if (facts.isNotEmpty()) {
            append(" Structural execution memory: ")
            append(facts.joinToString("; "))
            append('.')
        }
    }
}

private fun safeMemoryValue(value: String?): String? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.map { char -> if (char.isLetterOrDigit() || char in SAFE_MEMORY_PUNCTUATION) char else '_' }
    ?.joinToString("")
    ?.take(MAX_EXECUTION_MEMORY_VALUE_CHARS)

private const val MAX_EXECUTION_MEMORY_FACTS = 12
private const val MAX_EXECUTION_MEMORY_VALUE_CHARS = 160
private val SAFE_MEMORY_PUNCTUATION = setOf('/', '\\', '.', '-', '_', ':', '@')

class ContextBudgetExceededException(message: String) : IllegalArgumentException(message)
