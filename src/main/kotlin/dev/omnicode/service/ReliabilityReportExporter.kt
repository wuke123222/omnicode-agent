package dev.omnicode.service

import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a bounded, redacted reliability report for the free task audit workflow. The report is
 * intentionally textual: it never includes prompts, credentials, binary attachments or full
 * tool arguments, so exporting it cannot silently become a repository snapshot.
 */
object ReliabilityReportExporter {
    private const val MAX_REPORT_CHARS = 160_000
    private const val MAX_EVENT_CHARS = 320
    private val timeFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
    private val redactor = DefaultSensitiveDataRedactor()

    fun markdown(snapshot: WorkflowReliabilitySnapshot): String {
        val report = buildString {
            appendLine("# OmniCode 任务可靠性报告")
            appendLine()
            appendLine("- Workflow：`${snapshot.workflowId.take(120)}`")
            appendLine("- 总耗时：${formatDuration(snapshot.totalDurationMillis)}")
            appendLine("- 模型请求：${snapshot.modelRequestCount}")
            appendLine("- 工具失败：${snapshot.toolFailureCount}")
            appendLine("- 供应商重试：${snapshot.retryCount}")
            appendLine("- 恢复点：${snapshot.recoveryPointCount}")
            appendLine()
            appendLine("## 阶段")
            appendLine()
            if (snapshot.stages.isEmpty()) {
                appendLine("暂无阶段记录。")
            } else {
                appendLine("| 阶段 | 耗时 | 结果 | 说明 |")
                appendLine("| --- | ---: | --- | --- |")
                snapshot.stages.forEach { stage ->
                    appendLine(
                        "| ${cell(stage.stage)} | ${formatDuration(stage.durationMillis)} | " +
                            "${stage.success?.let { if (it) "完成" else "失败" } ?: "进行中"} | " +
                            cell(stage.detail),
                    )
                }
            }
            if (snapshot.retryReasons.isNotEmpty()) {
                appendLine()
                appendLine("## 重试原因")
                snapshot.retryReasons.forEach { appendLine("- ${cell(it)}") }
            }
            appendLine()
            appendLine("## 事件轨迹（有界摘要）")
            appendLine()
            snapshot.events.asReversed().take(120).forEach { event ->
                val stage = event.stage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                appendLine(
                    "- ${timeFormat.format(event.recordedAt)} · ${event.type.name.lowercase()}$stage · " +
                        cell(event.message, MAX_EVENT_CHARS),
                )
            }
            appendLine()
            appendLine("> 本报告由 OmniCode 可靠性中心生成。内容是脱敏的阶段证据摘要，不是完整会话或环境快照。")
        }
        return report.take(MAX_REPORT_CHARS)
    }

    private fun cell(value: String, maxChars: Int = 1_000): String = redactor.redact(value)
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", " ")
        .trim()
        .take(maxChars)

    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis.coerceAtLeast(0)} ms"
        millis < 60_000 -> "${"%.1f".format(millis / 1_000.0)} s"
        else -> "${millis / 60_000}m ${millis / 1_000 % 60}s"
    }
}
