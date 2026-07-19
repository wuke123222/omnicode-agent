package dev.omnicode.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReproducibleResearchPackageExporterTest {
    @Test
    fun `exports redacted bounded reproducible markdown without image payloads`() {
        val opaqueSecret = "opaque-provider-secret-12345"
        val imagePayload = "IMAGE_BASE64_MUST_NOT_LEAK_abcdef012345"
        val toolImagePayload = "TOOL_IMAGE_DATA_MUST_NOT_LEAK_1234567890"
        val arguments = JsonObject().apply {
            add("argv", JsonArray().apply {
                add("./gradlew")
                add("test")
            })
            addProperty("api_key", opaqueSecret)
            addProperty("image_base64", toolImagePayload)
        }
        val messages = listOf(
            ConversationMessage(
                MessageRole.USER,
                listOf(
                    ContentBlock.Text("Investigate the result with ````nested fence```` and $opaqueSecret"),
                    ContentBlock.Image("screen.png", "image/png", imagePayload, 1_024),
                ),
            ),
            ConversationMessage(
                MessageRole.ASSISTANT,
                listOf(
                    ContentBlock.ToolCall("call-1", "run_command", arguments),
                    ContentBlock.ToolResult("call-1", "Exit code: 0\nAll tests passed", isError = false),
                    ContentBlock.Text("The evidence should still be verified against its original source."),
                ),
            ),
        )
        val generatedAt = Instant.parse("2026-07-19T10:15:30Z")
        val exporter = ReproducibleResearchPackageExporter(
            redactor = DefaultSensitiveDataRedactor(listOf(opaqueSecret)),
        )

        val result = exporter.export(
            ResearchPackageExportRequest(
                messages = messages,
                mode = AgentMode.AGENT,
                provider = "OpenAI",
                model = "coding-model",
                projectName = "Research Demo",
                generatedAt = generatedAt,
            ),
        )

        assertTrue(result.markdown.contains("## 元数据"))
        assertTrue(result.markdown.contains("## 研究问题"))
        assertTrue(result.markdown.contains("## 对话记录"))
        assertTrue(result.markdown.contains("## 工具与命令证据"))
        assertTrue(result.markdown.contains("## 复现清单"))
        assertTrue(result.markdown.contains("## 限制"))
        assertTrue(result.markdown.contains("## 引用核对清单"))
        assertTrue(result.markdown.contains("| 导出时模式 | AGENT |"))
        assertTrue(result.markdown.contains("| 导出时供应商 | OpenAI |"))
        assertTrue(result.markdown.contains("| 导出时模型 | coding-model |"))
        assertTrue(result.markdown.contains("[REDACTED]"))
        assertFalse(result.markdown.contains(opaqueSecret))
        assertFalse(result.markdown.contains(imagePayload))
        assertFalse(result.markdown.contains(toolImagePayload))
        assertTrue(result.markdown.contains("screen.png"))
        assertTrue(result.markdown.contains("image/png"))
        assertTrue(result.markdown.contains("1024 bytes"))
        assertTrue(result.markdown.contains("base64 已省略"))
        assertTrue(result.markdown.contains("`````text\nInvestigate"), "fence must exceed runs inside content")
        assertTrue(result.markdown.contains("run_command"))
        assertTrue(result.markdown.contains("成功：Exit code: 0"))
        assertEquals("omnicode-research-research-demo-20260719-101530.md", result.suggestedFileName)
        assertEquals(2, result.sourceMessageCount)
        assertEquals(2, result.exportedMessageCount)
        assertEquals(2, result.inputMessageCount)
        assertEquals(0, result.excludedSystemMessageCount)
        assertEquals(1, result.evidenceCount)
        assertFalse(result.truncated)
        assertTrue(
            result.markdown.toByteArray(StandardCharsets.UTF_8).size <=
                ReproducibleResearchPackageExporter.DEFAULT_MAX_EXPORT_BYTES,
        )
    }

    @Test
    fun `removes inline image data urls from every free text surface`() {
        val data = "data:image/png;base64," + "A".repeat(500)
        val result = ReproducibleResearchPackageExporter().export(
            ResearchPackageExportRequest(
                messages = listOf(ConversationMessage(MessageRole.USER, "inspect $data now")),
                mode = AgentMode.PLAN,
                provider = "provider",
                model = "model",
                projectName = "project",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertFalse(result.markdown.contains(data))
        assertTrue(result.markdown.contains("[IMAGE_BASE64_OMITTED]"))
    }

    @Test
    fun `system messages and absolute project roots are excluded before every export stage`() {
        val root = "/Users/private-user/SecretResearchProject"
        val internalPrompt = "INTERNAL_AGENT_POLICY_MUST_NOT_EXPORT"
        val result = ReproducibleResearchPackageExporter().export(
            ResearchPackageExportRequest(
                messages = listOf(
                    ConversationMessage(
                        MessageRole.SYSTEM,
                        "You are internal. Project root: $root. $internalPrompt",
                    ),
                    ConversationMessage(MessageRole.USER, "actual research question"),
                    ConversationMessage(MessageRole.ASSISTANT, "public result"),
                ),
                mode = AgentMode.RESEARCH,
                provider = "provider",
                model = "model",
                projectName = "safe-project",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertFalse(result.markdown.contains(root))
        assertFalse(result.markdown.contains(internalPrompt))
        assertFalse(result.markdown.contains("MessageRole.SYSTEM"))
        assertTrue(result.markdown.contains("actual research question"))
        assertTrue(result.markdown.contains("public result"))
        assertTrue(result.markdown.contains("| 输入消息数 | 3 |"))
        assertTrue(result.markdown.contains("| 排除 SYSTEM 消息数 | 1 |"))
        assertTrue(result.markdown.contains("| 可导出源消息数 | 2 |"))
        assertTrue(result.markdown.contains("| 实际选取消息数 | 2 |"))
        assertEquals(3, result.inputMessageCount)
        assertEquals(1, result.excludedSystemMessageCount)
        assertEquals(2, result.sourceMessageCount)
        assertEquals(2, result.exportedMessageCount)
        assertEquals(0, result.evidenceCount)
    }

    @Test
    fun `filters raw png jpeg and url safe image base64 magic headers`() {
        val png = "iVBORw0KGgo" + "A".repeat(160)
        val jpeg = "/9j/" + "B".repeat(160)
        val urlSafeJpeg = "_9j_" + "C-_".repeat(80)
        val result = ReproducibleResearchPackageExporter().export(
            ResearchPackageExportRequest(
                messages = listOf(
                    ConversationMessage(MessageRole.USER, "png=$png; jpeg=$jpeg; safe=$urlSafeJpeg"),
                ),
                mode = AgentMode.RESEARCH,
                provider = "provider",
                model = "model",
                projectName = "project",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertFalse(result.markdown.contains(png))
        assertFalse(result.markdown.contains(jpeg))
        assertFalse(result.markdown.contains(urlSafeJpeg))
        assertTrue(result.markdown.windowed("[IMAGE_BASE64_OMITTED]".length)
            .count { it == "[IMAGE_BASE64_OMITTED]" } >= 3)
    }

    @Test
    fun `pre redaction block and total character budgets omit unsafe oversized inputs`() {
        val oversizedSentinel = "OVERSIZED_RAW_BLOCK_MUST_NOT_EXPORT"
        val oversizedBlock = oversizedSentinel + "x".repeat(300_000)
        val budgetPressure = (0 until 30).map { index ->
            ConversationMessage(MessageRole.ASSISTANT, "budget-$index:" + "y".repeat(100_000))
        }
        val result = ReproducibleResearchPackageExporter().export(
            ResearchPackageExportRequest(
                messages = listOf(
                    ConversationMessage(MessageRole.USER, oversizedBlock),
                    ConversationMessage(MessageRole.USER, "bounded question"),
                ) + budgetPressure,
                mode = AgentMode.RESEARCH,
                provider = "provider",
                model = "model",
                projectName = "project",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertFalse(result.markdown.contains(oversizedSentinel))
        assertTrue(result.markdown.contains("内容在脱敏前已省略"))
        assertTrue(result.markdown.contains("输入预处理总预算"))
        assertTrue(result.truncated)
        assertTrue(
            result.markdown.toByteArray(StandardCharsets.UTF_8).size <=
                ReproducibleResearchPackageExporter.DEFAULT_MAX_EXPORT_BYTES,
        )
    }

    @Test
    fun `hard byte limit keeps every required section and reports omissions`() {
        val messages = (0 until 220).map { index ->
            ConversationMessage(
                if (index == 0) MessageRole.USER else MessageRole.ASSISTANT,
                "message-$index " + "多字节内容".repeat(4_000),
            )
        }
        val maxBytes = ReproducibleResearchPackageExporter.MIN_EXPORT_BYTES
        val result = ReproducibleResearchPackageExporter(maxBytes = maxBytes).export(
            ResearchPackageExportRequest(
                messages = messages,
                mode = AgentMode.PLAN,
                provider = "p",
                model = "m",
                projectName = "p",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertTrue(result.truncated)
        assertEquals(220, result.sourceMessageCount)
        assertEquals(160, result.exportedMessageCount)
        assertTrue(result.markdown.toByteArray(StandardCharsets.UTF_8).size <= maxBytes)
        listOf("## 研究问题", "## 对话记录", "## 工具与命令证据", "## 复现清单", "## 限制", "## 引用核对清单")
            .forEach { heading -> assertTrue(result.markdown.contains(heading), heading) }
        assertTrue(result.markdown.contains("中间省略消息：60"))
    }

    @Test
    fun `a single oversized transcript block is visibly and structurally marked truncated`() {
        val result = ReproducibleResearchPackageExporter().export(
            ResearchPackageExportRequest(
                messages = listOf(ConversationMessage(MessageRole.USER, "x".repeat(40_000))),
                mode = AgentMode.AGENT,
                provider = "p",
                model = "m",
                projectName = "p",
                generatedAt = Instant.EPOCH,
            ),
        )

        assertTrue(result.truncated)
        assertEquals(1, result.sourceMessageCount)
        assertEquals(1, result.exportedMessageCount)
        assertTrue(result.markdown.contains("内容已截断"))
        assertTrue(result.markdown.contains("本次内容截断：是"))
    }

    @Test
    fun `suggested filename is redacted portable and bounded`() {
        val secret = "sk-proj-abcdefghijklmno"
        val exporter = ReproducibleResearchPackageExporter(
            redactor = DefaultSensitiveDataRedactor(listOf(secret)),
        )

        val name = exporter.suggestedFileName("../../$secret/中文 项目", Instant.EPOCH)

        assertFalse(name.contains(secret))
        assertFalse(name.contains("/"))
        assertTrue(name.startsWith("omnicode-research-redacted-"))
        assertTrue(name.endsWith(".md"))
        assertTrue(name.length < 100)
    }
}
