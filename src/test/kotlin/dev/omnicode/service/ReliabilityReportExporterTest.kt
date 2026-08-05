package dev.omnicode.service

import dev.omnicode.persistence.WorkflowEventRecord
import dev.omnicode.persistence.WorkflowEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class ReliabilityReportExporterTest {
    @Test
    fun `report is useful but bounded and redacted`() {
        val snapshot = WorkflowReliabilitySnapshot(
            workflowId = "workflow-123",
            totalDurationMillis = 2_500,
            modelRequestCount = 2,
            toolFailureCount = 1,
            retryCount = 1,
            retryReasons = listOf("429 rate limit"),
            recoveryPointCount = 3,
            stages = listOf(WorkflowStageSummary("探索", 1_200, true, "读取 src/Main.kt")),
            events = listOf(
                WorkflowEventRecord(
                    id = "event-1",
                    workflowId = "workflow-123",
                    runId = "run-1",
                    projectId = "project-1",
                    type = WorkflowEventType.TOOL_FAILURE,
                    stage = "探索",
                    message = "secret=sk-test should not be copied as a prompt",
                    recordedAt = Instant.parse("2026-08-05T00:00:00Z"),
                ),
            ),
        )

        val report = ReliabilityReportExporter.markdown(snapshot)

        assertTrue(report.contains("任务可靠性报告"))
        assertTrue(report.contains("429 rate limit"))
        assertTrue(report.length <= 160_000)
        assertFalse(report.contains("sk-test"), "reports must not expose credentials")
    }
}
