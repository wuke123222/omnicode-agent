package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineeringDigestExporterTest {
    @Test
    fun `digest contains version diff and task progress without prompts`() {
        val digest = EngineeringDigestExporter.markdown(
            EngineeringDigestInput(
                projectName = "demo",
                periodStart = Instant.parse("2026-07-29T00:00:00Z"),
                generatedAt = Instant.parse("2026-08-05T00:00:00Z"),
                git = GitProgressSnapshot(
                    branch = "main",
                    commits = listOf(GitCommitSummary("abc123", "2026-08-01", "修复登录")),
                    versionDeltas = listOf(GitVersionDelta("v1.0.0", "v1.1.0", "2 files changed, 10 insertions(+), 2 deletions(-)")),
                    workingTree = "## main",
                    warnings = emptyList(),
                ),
                tasks = listOf(
                    UnifiedTaskEntry(
                        taskId = "task-1",
                        workflowId = null,
                        conversationId = null,
                        title = "完成登录流程",
                        status = UnifiedTaskStatus.COMPLETED,
                        mode = AgentMode.AGENT,
                        strategy = AgentExecutionStrategy.SINGLE,
                        updatedAt = Instant.parse("2026-08-04T12:00:00Z"),
                    ),
                ),
            ),
        )

        assertTrue(digest.contains("v1.0.0"))
        assertTrue(digest.contains("完成登录流程"))
        assertTrue(digest.contains("2 files changed"))
        assertFalse(digest.contains("conversationHistory"))
    }
}
