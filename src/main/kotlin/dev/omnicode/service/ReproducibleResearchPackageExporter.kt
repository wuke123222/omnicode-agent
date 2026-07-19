package dev.omnicode.service

import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ResearchPackageExportRequest(
    val messages: List<ConversationMessage>,
    val mode: AgentMode,
    val provider: String,
    val model: String,
    val projectName: String,
    val generatedAt: Instant = Instant.now(),
)

data class ReproducibleResearchPackage(
    val markdown: String,
    val suggestedFileName: String,
    val generatedAt: Instant,
    /** Non-SYSTEM messages eligible for export before selection/truncation. */
    val sourceMessageCount: Int,
    val exportedMessageCount: Int,
    val evidenceCount: Int,
    val truncated: Boolean,
    val inputMessageCount: Int = sourceMessageCount,
    val excludedSystemMessageCount: Int = 0,
)

/**
 * Builds a bounded, redacted research artifact without reading ambient project or credential state.
 * Callers that know opaque active secrets should construct the required
 * [DefaultSensitiveDataRedactor] with those values before exporting.
 */
class ReproducibleResearchPackageExporter(
    private val redactor: DefaultSensitiveDataRedactor = DefaultSensitiveDataRedactor(),
    private val maxBytes: Int = DEFAULT_MAX_EXPORT_BYTES,
) {
    init {
        require(maxBytes in MIN_EXPORT_BYTES..MAX_EXPORT_BYTES) {
            "Research package size limit must be between $MIN_EXPORT_BYTES and $MAX_EXPORT_BYTES bytes."
        }
    }

    fun export(request: ResearchPackageExportRequest): ReproducibleResearchPackage {
        val eligibleMessages = request.messages.mapIndexed(::IndexedMessage)
            .filterNot { it.message.role == MessageRole.SYSTEM }
        val excludedSystemMessageCount = request.messages.size - eligibleMessages.size
        val selected = selectMessages(eligibleMessages)
        val evidence = collectEvidence(selected.messages)
        val imageCount = eligibleMessages.sumOf { indexed ->
            indexed.message.blocks.count { it is ContentBlock.Image }
        }
        val inputBudget = InputPreprocessingBudget()
        val metadata = renderMetadata(
            request = request,
            selected = selected,
            sourceMessageCount = eligibleMessages.size,
            excludedSystemMessageCount = excludedSystemMessageCount,
            inputBudget = inputBudget,
        )
        val provisionalTail = renderVerificationTail(
            truncated = true,
            imageCount = imageCount,
            omittedMessageCount = selected.omittedCount,
            excludedSystemMessageCount = excludedSystemMessageCount,
        )
        val fixedBytes = utf8Size(metadata) + utf8Size(provisionalTail)
        check(fixedBytes < maxBytes) { "Research package metadata exceeds the configured size limit." }

        val variableBudget = maxBytes - fixedBytes
        val questionBudget = minOf(MAX_QUESTION_SECTION_BYTES, variableBudget / 6)
        val evidenceBudget = minOf(MAX_EVIDENCE_SECTION_BYTES, variableBudget / 4)
        val conversationBudget = variableBudget - questionBudget - evidenceBudget

        val question = renderResearchQuestion(eligibleMessages, questionBudget, inputBudget)
        val conversation = renderConversation(
            selected.messages,
            selected.omittedCount,
            conversationBudget,
            inputBudget,
        )
        val evidenceSection = renderEvidence(evidence, evidenceBudget, inputBudget)
        val truncated = selected.omittedCount > 0 || inputBudget.truncated || question.truncated ||
            conversation.truncated || evidence.truncated || evidenceSection.truncated
        val tail = renderVerificationTail(
            truncated,
            imageCount,
            selected.omittedCount,
            excludedSystemMessageCount,
        )
        val markdown = metadata + question.text + conversation.text + evidenceSection.text + tail
        check(utf8Size(markdown) <= maxBytes) { "Research package exceeded its hard byte limit." }

        return ReproducibleResearchPackage(
            markdown = markdown,
            suggestedFileName = suggestedFileName(request.projectName, request.generatedAt),
            generatedAt = request.generatedAt,
            sourceMessageCount = eligibleMessages.size,
            exportedMessageCount = selected.messages.size,
            evidenceCount = evidence.items.size,
            truncated = truncated,
            inputMessageCount = request.messages.size,
            excludedSystemMessageCount = excludedSystemMessageCount,
        )
    }

    fun suggestedFileName(projectName: String, generatedAt: Instant = Instant.now()): String {
        val safeProject = protectedText(projectName, MAX_METADATA_CHARS, InputPreprocessingBudget()).text
            .lowercase(Locale.ROOT)
            .replace(NON_FILE_NAME, "-")
            .trim('-')
            .take(MAX_FILE_SLUG_CHARS)
            .ifBlank { "project" }
        return "omnicode-research-$safeProject-${FILE_TIME_FORMAT.format(generatedAt)}.md"
    }

    private fun renderMetadata(
        request: ResearchPackageExportRequest,
        selected: SelectedMessages,
        sourceMessageCount: Int,
        excludedSystemMessageCount: Int,
        inputBudget: InputPreprocessingBudget,
    ): String = buildString {
        appendLine("# 可复现实验研究包")
        appendLine()
        appendLine("> 由 OmniCode Agent 从当前会话生成。自由文本已执行敏感信息脱敏和大小限制。")
        appendLine()
        appendLine("## 元数据")
        appendLine()
        appendLine("| 字段 | 值 |")
        appendLine("| --- | --- |")
        appendLine("| 格式版本 | 1 |")
        appendLine("| 生成时间（UTC） | ${tableCell(request.generatedAt.toString(), MAX_METADATA_CHARS, inputBudget)} |")
        appendLine("| 项目 | ${tableCell(request.projectName, MAX_METADATA_CHARS, inputBudget)} |")
        appendLine("| 导出时模式 | ${tableCell(request.mode.name, MAX_METADATA_CHARS, inputBudget)} |")
        appendLine("| 导出时供应商 | ${tableCell(request.provider, MAX_METADATA_CHARS, inputBudget)} |")
        appendLine("| 导出时模型 | ${tableCell(request.model, MAX_METADATA_CHARS, inputBudget)} |")
        appendLine("| 输入消息数 | ${request.messages.size} |")
        appendLine("| 排除 SYSTEM 消息数 | $excludedSystemMessageCount |")
        appendLine("| 可导出源消息数 | $sourceMessageCount |")
        appendLine("| 实际选取消息数 | ${selected.messages.size} |")
        appendLine("| 脱敏 | DefaultSensitiveDataRedactor |")
        appendLine()
    }

    private fun renderResearchQuestion(
        messages: List<IndexedMessage>,
        maxSectionBytes: Int,
        inputBudget: InputPreprocessingBudget,
    ): RenderedSection {
        val builder = BoundedSectionBuilder(maxSectionBytes)
        builder.appendRequired("## 研究问题\n\n")
        val firstUser = messages.firstOrNull { it.message.role == MessageRole.USER }?.message
        var sourceTruncated = false
        val question = firstUser?.blocks.orEmpty().mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> protectedText(block.text, MAX_QUESTION_CHARS, inputBudget).also {
                    sourceTruncated = sourceTruncated || it.truncated
                }.text
                is ContentBlock.Image -> imageMetadata(block, inputBudget)
                is ContentBlock.ToolCall,
                is ContentBlock.ToolResult,
                -> null
            }
        }.joinToString("\n\n").ifBlank { "未记录独立的用户研究问题。" }
        builder.appendFenced(question, "text")
        if (sourceTruncated) builder.markTruncated()
        return builder.build("研究问题因导出上限被截断。")
    }

    private fun renderConversation(
        messages: List<IndexedMessage>,
        omittedMessageCount: Int,
        maxSectionBytes: Int,
        inputBudget: InputPreprocessingBudget,
    ): RenderedSection {
        val builder = BoundedSectionBuilder(maxSectionBytes)
        builder.appendRequired("## 对话记录\n\n")
        if (omittedMessageCount > 0) {
            builder.appendAtomic("> 为控制导出大小，已省略中间 $omittedMessageCount 条消息。\n\n")
        }
        if (messages.isEmpty()) {
            builder.appendAtomic("_当前会话没有可导出的消息。_\n\n")
        }
        for (indexed in messages) {
            val messageBudget = minOf(MAX_MESSAGE_EXPORT_BYTES, builder.remainingBytes)
            if (messageBudget < MIN_MESSAGE_EXPORT_BYTES) {
                builder.markTruncated()
                break
            }
            val rendered = renderMessage(indexed, messageBudget, inputBudget)
            if (!builder.appendAtomic(rendered.text)) {
                builder.markTruncated()
                break
            }
            if (rendered.truncated) builder.markTruncated()
        }
        return builder.build("较晚的对话内容因导出上限被截断。")
    }

    private fun renderMessage(
        indexed: IndexedMessage,
        maxMessageBytes: Int,
        inputBudget: InputPreprocessingBudget,
    ): RenderedSection {
        val builder = BoundedSectionBuilder(maxMessageBytes)
        builder.appendRequired("### 消息 ${indexed.originalIndex + 1} · ${indexed.message.role.name}\n\n")
        for ((blockIndex, block) in indexed.message.blocks.withIndex()) {
            var sourceTruncated = false
            val chunk = when (block) {
                is ContentBlock.Text -> {
                    val text = protectedText(block.text, MAX_TRANSCRIPT_BLOCK_CHARS, inputBudget)
                    sourceTruncated = text.truncated
                    buildString {
                        appendLine("#### 文本 ${blockIndex + 1}")
                        appendLine()
                        append(safeFence(text.text, "text"))
                        if (text.truncated) {
                            appendLine("_该文本块已截断。_")
                            appendLine()
                        }
                    }
                }
                is ContentBlock.Image -> buildString {
                    appendLine("#### 图片 ${blockIndex + 1}")
                    appendLine()
                    appendLine("- ${inlineText(imageMetadata(block, inputBudget), MAX_IMAGE_METADATA_CHARS, inputBudget)}")
                    appendLine()
                }
                is ContentBlock.ToolCall -> buildString {
                    val detail = protectedText(block.arguments.toString(), MAX_TOOL_DETAIL_CHARS, inputBudget)
                    sourceTruncated = detail.truncated
                    appendLine(
                        "#### 工具调用 ${blockIndex + 1} · " +
                            inlineText(block.name, MAX_TOOL_NAME_CHARS, inputBudget),
                    )
                    appendLine()
                    appendLine("- 调用 ID：${inlineText(block.id, MAX_TOOL_ID_CHARS, inputBudget)}")
                    appendLine()
                    append(safeFence(detail.text, "json"))
                }
                is ContentBlock.ToolResult -> buildString {
                    val detail = protectedText(block.content, MAX_TOOL_DETAIL_CHARS, inputBudget)
                    sourceTruncated = detail.truncated
                    appendLine("#### 工具结果 ${blockIndex + 1} · ${if (block.isError) "失败" else "成功"}")
                    appendLine()
                    appendLine("- 调用 ID：${inlineText(block.toolCallId, MAX_TOOL_ID_CHARS, inputBudget)}")
                    appendLine()
                    append(safeFence(detail.text, "text"))
                }
            }
            if (sourceTruncated) builder.markTruncated()
            if (!builder.appendAtomic(chunk)) {
                builder.markTruncated()
                break
            }
        }
        return builder.build("该消息的剩余内容已截断。")
    }

    private fun renderEvidence(
        evidence: CollectedEvidence,
        maxSectionBytes: Int,
        inputBudget: InputPreprocessingBudget,
    ): RenderedSection {
        val builder = BoundedSectionBuilder(maxSectionBytes)
        builder.appendRequired(
            "## 工具与命令证据\n\n" +
                "| # | 消息 | 类型 | 工具/命令 | 输入证据 | 结果 |\n" +
                "| --- | --- | --- | --- | --- | --- |\n",
        )
        if (evidence.items.isEmpty()) {
            builder.appendAtomic("| — | — | — | — | 当前会话没有工具调用记录 | — |\n\n")
        } else {
            for ((index, item) in evidence.items.withIndex()) {
                if (item.input.length > MAX_EVIDENCE_CELL_CHARS || item.result.length > MAX_EVIDENCE_CELL_CHARS) {
                    builder.markTruncated()
                }
                val row = "| ${index + 1} | ${item.messageNumber} | ${tableCell(item.type, 40, inputBudget)} | " +
                    "${tableCell(item.name, MAX_TOOL_NAME_CHARS, inputBudget)} | " +
                    "${tableCell(item.input, MAX_EVIDENCE_CELL_CHARS, inputBudget)} | " +
                    "${tableCell(item.result, MAX_EVIDENCE_CELL_CHARS, inputBudget)} |\n"
                if (!builder.appendAtomic(row)) {
                    builder.markTruncated()
                    break
                }
            }
            builder.appendAtomic("\n")
        }
        if (evidence.truncated) builder.markTruncated()
        return builder.build("部分工具或命令证据因数量/大小上限被省略。")
    }

    private fun renderVerificationTail(
        truncated: Boolean,
        imageCount: Int,
        omittedMessageCount: Int,
        excludedSystemMessageCount: Int,
    ): String = buildString {
        appendLine("## 复现清单")
        appendLine()
        appendLine("- [ ] 记录并检出准确的仓库提交、分支及未提交差异。")
        appendLine("- [ ] 记录 IDE、JDK、操作系统、依赖管理器和关键工具版本。")
        appendLine("- [ ] 使用元数据中相同的供应商、模型与 Agent 模式；不要在文件中记录 API Key。")
        appendLine("- [ ] 按证据表顺序人工核对并重放命令；先确认工作目录、参数和沙箱权限。")
        appendLine("- [ ] 比较退出码、测试结果、文件差异和模型输出，并记录不可重复的偏差。")
        appendLine("- [ ] 将必要的输入数据、随机种子和环境变量名称另行登记，敏感值保留在安全存储中。")
        appendLine()
        appendLine("## 限制")
        appendLine()
        appendLine("- 脱敏是纵深防御，不等同于人工安全审查；分享前仍需逐项检查。")
        appendLine("- 图片二进制/base64 未导出，仅保留文件名、媒体类型和字节数；图片数：$imageCount。")
        appendLine("- 模型输出和工具结果属于会话证据，不代表事实已被独立验证。")
        appendLine("- 模型服务可能具有非确定性；本包不包含 API 凭据、完整进程环境或宿主机状态。")
        appendLine("- SYSTEM 消息默认不导出；本次已排除：$excludedSystemMessageCount。")
        appendLine("- 中间省略消息：$omittedMessageCount；本次内容截断：${if (truncated) "是" else "否"}。")
        appendLine()
        appendLine("## 引用核对清单")
        appendLine()
        appendLine("- [ ] 每个外部事实声明均有可定位的一手来源或明确标记为推断。")
        appendLine("- [ ] 逐一打开 URL、论文、Issue、文档章节或代码行，确认仍可访问且与声明直接相关。")
        appendLine("- [ ] 核对来源发布日期、事件发生日期、版本和适用平台，避免使用过期资料。")
        appendLine("- [ ] 引文保持最小必要长度，并与转述、模型结论和工具输出明确区分。")
        appendLine("- [ ] 删除包含凭据、私有仓库地址、签名 URL、个人数据或内部主机名的引用。")
    }

    private fun collectEvidence(messages: List<IndexedMessage>): CollectedEvidence {
        val items = mutableListOf<MutableEvidence>()
        val callsById = linkedMapOf<String, MutableEvidence>()
        var truncated = false
        messages.forEach { indexed ->
            indexed.message.blocks.forEach { block ->
                when (block) {
                    is ContentBlock.ToolCall -> {
                        if (items.size >= MAX_EVIDENCE_ITEMS) {
                            truncated = true
                            return@forEach
                        }
                        val command = block.name.equals("run_command", ignoreCase = true)
                        val evidence = MutableEvidence(
                            messageNumber = indexed.originalIndex + 1,
                            type = if (command) "命令" else "工具",
                            name = block.name,
                            input = block.arguments.toString(),
                            result = "未记录结果",
                        )
                        items += evidence
                        callsById[block.id] = evidence
                    }
                    is ContentBlock.ToolResult -> {
                        val matching = callsById[block.toolCallId]
                        if (matching != null) {
                            matching.result = (if (block.isError) "失败：" else "成功：") + block.content
                        } else if (items.size < MAX_EVIDENCE_ITEMS) {
                            items += MutableEvidence(
                                messageNumber = indexed.originalIndex + 1,
                                type = "工具结果",
                                name = "未匹配调用 ${block.toolCallId}",
                                input = "—",
                                result = (if (block.isError) "失败：" else "成功：") + block.content,
                            )
                        } else {
                            truncated = true
                        }
                    }
                    is ContentBlock.Image,
                    is ContentBlock.Text,
                    -> Unit
                }
            }
        }
        return CollectedEvidence(
            items = items.map { item ->
                Evidence(
                    messageNumber = item.messageNumber,
                    type = item.type,
                    name = item.name,
                    input = item.input,
                    result = item.result,
                )
            },
            truncated = truncated,
        )
    }

    private fun selectMessages(messages: List<IndexedMessage>): SelectedMessages {
        if (messages.size <= MAX_EXPORTED_MESSAGES) return SelectedMessages(messages, 0)
        val head = messages.take(PRESERVED_HEAD_MESSAGES)
        val tail = messages.takeLast(MAX_EXPORTED_MESSAGES - PRESERVED_HEAD_MESSAGES)
        return SelectedMessages(head + tail, messages.size - head.size - tail.size)
    }

    private fun imageMetadata(block: ContentBlock.Image, inputBudget: InputPreprocessingBudget): String {
        val fileName = protectedText(block.fileName, MAX_IMAGE_METADATA_CHARS, inputBudget).text
        val mediaType = protectedText(block.mediaType, MAX_METADATA_CHARS, inputBudget).text
        return "图片附件：$fileName；类型：$mediaType；大小：${block.byteSize.coerceAtLeast(0)} bytes；base64 已省略"
    }

    private fun tableCell(value: String, maxChars: Int, inputBudget: InputPreprocessingBudget): String =
        inlineText(value, maxChars, inputBudget)
        .replace("|", "&#124;")
        .replace("\r\n", "<br>")
        .replace('\r', '\n')
        .replace("\n", "<br>")

    private fun inlineText(value: String, maxChars: Int, inputBudget: InputPreprocessingBudget): String =
        protectedText(value, maxChars, inputBudget).text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("`", "&#96;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")

    private fun protectedText(
        value: String,
        maxChars: Int,
        inputBudget: InputPreprocessingBudget,
    ): ProtectedText {
        inputBudget.rejectionReason(value.length)?.let { reason ->
            return ProtectedText("[内容在脱敏前已省略：$reason]", true)
        }
        val redacted = redactor.redact(value)
            .replace(DATA_IMAGE_URL, "[IMAGE_BASE64_OMITTED]")
            .replace(JSON_IMAGE_BASE64) { match ->
                "${match.groupValues[1]}[IMAGE_BASE64_OMITTED]${match.groupValues[3]}"
            }
            .replace(RAW_IMAGE_BASE64, "[IMAGE_BASE64_OMITTED]")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character -> character == '\n' || character == '\t' || !character.isISOControl() }
        if (redacted.length <= maxChars) return ProtectedText(redacted, false)
        inputBudget.markTruncated()
        return ProtectedText(redacted.take(maxChars).trimEnd() + "\n[内容已截断]", true)
    }

    private data class IndexedMessage(val originalIndex: Int, val message: ConversationMessage)
    private data class SelectedMessages(val messages: List<IndexedMessage>, val omittedCount: Int)
    private data class ProtectedText(val text: String, val truncated: Boolean)
    private data class RenderedSection(val text: String, val truncated: Boolean)

    /** Enforces CPU/memory bounds before any free-form input reaches redaction regexes. */
    private class InputPreprocessingBudget(
        private var remainingChars: Int = MAX_INPUT_TOTAL_CHARS,
    ) {
        var truncated: Boolean = false
            private set

        fun rejectionReason(inputChars: Int): String? {
            if (inputChars > MAX_INPUT_BLOCK_CHARS) {
                truncated = true
                return "单块 $inputChars 字符，超过 $MAX_INPUT_BLOCK_CHARS 字符上限"
            }
            if (inputChars > remainingChars) {
                truncated = true
                return "超过 $MAX_INPUT_TOTAL_CHARS 字符输入预处理总预算"
            }
            remainingChars -= inputChars
            return null
        }

        fun markTruncated() {
            truncated = true
        }
    }

    private data class Evidence(
        val messageNumber: Int,
        val type: String,
        val name: String,
        val input: String,
        val result: String,
    )
    private data class MutableEvidence(
        val messageNumber: Int,
        val type: String,
        val name: String,
        val input: String,
        var result: String,
    )
    private data class CollectedEvidence(val items: List<Evidence>, val truncated: Boolean)

    private class BoundedSectionBuilder(private val maxBytes: Int) {
        private val content = StringBuilder()
        private val markerReserve = utf8Size("\n> [导出内容已截断]\n\n")
        private var usedBytes = 0
        var truncated: Boolean = false
            private set

        val remainingBytes: Int get() = (maxBytes - markerReserve - usedBytes).coerceAtLeast(0)

        fun appendRequired(value: String) {
            check(appendAtomic(value)) { "Research package section budget is too small for its heading." }
        }

        fun appendAtomic(value: String): Boolean {
            val bytes = utf8Size(value)
            if (bytes > remainingBytes) {
                truncated = true
                return false
            }
            content.append(value)
            usedBytes += bytes
            return true
        }

        fun appendFenced(value: String, info: String) {
            val available = remainingBytes
            if (available <= MIN_FENCE_BYTES) {
                truncated = true
                return
            }
            val (fence, wasTruncated) = boundedFence(value, info, available)
            appendAtomic(fence)
            if (wasTruncated) truncated = true
        }

        fun markTruncated() {
            truncated = true
        }

        fun build(detail: String): RenderedSection {
            if (truncated) {
                val marker = "\n> ${detail.trim()}\n\n"
                if (utf8Size(marker) <= maxBytes - usedBytes) {
                    content.append(marker)
                    usedBytes += utf8Size(marker)
                } else {
                    val fallback = "\n> [导出内容已截断]\n\n"
                    if (utf8Size(fallback) <= maxBytes - usedBytes) content.append(fallback)
                }
            }
            return RenderedSection(content.toString(), truncated)
        }
    }

    companion object {
        const val DEFAULT_MAX_EXPORT_BYTES: Int = 512 * 1024
        const val MAX_EXPORT_BYTES: Int = 2 * 1024 * 1024
        const val MIN_EXPORT_BYTES: Int = 16 * 1024

        private const val MAX_EXPORTED_MESSAGES = 160
        private const val PRESERVED_HEAD_MESSAGES = 8
        private const val MAX_EVIDENCE_ITEMS = 200
        private const val MAX_METADATA_CHARS = 240
        private const val MAX_FILE_SLUG_CHARS = 48
        private const val MAX_QUESTION_CHARS = 24_000
        private const val MAX_TRANSCRIPT_BLOCK_CHARS = 12_000
        private const val MAX_TOOL_DETAIL_CHARS = 8_000
        private const val MAX_TOOL_NAME_CHARS = 120
        private const val MAX_TOOL_ID_CHARS = 160
        private const val MAX_IMAGE_METADATA_CHARS = 300
        private const val MAX_EVIDENCE_CELL_CHARS = 700
        private const val MAX_INPUT_TOTAL_CHARS = 2_000_000
        private const val MAX_INPUT_BLOCK_CHARS = 256_000
        private const val MAX_MESSAGE_EXPORT_BYTES = 24 * 1024
        private const val MIN_MESSAGE_EXPORT_BYTES = 512
        private const val MAX_QUESTION_SECTION_BYTES = 32 * 1024
        private const val MAX_EVIDENCE_SECTION_BYTES = 96 * 1024
        private const val MIN_FENCE_BYTES = 64

        private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
        private val NON_FILE_NAME = Regex("[^a-z0-9]+")
        private val DATA_IMAGE_URL = Regex(
            pattern = "(?i)data:image/[a-z0-9.+-]+;base64,[a-z0-9+/_=\\-\\r\\n]+",
        )
        private val JSON_IMAGE_BASE64 = Regex(
            pattern = "(?i)([\"']?(?:image[_-]?(?:base64|data)|base64[_-]?(?:image|data))[\"']?\\s*[:=]\\s*[\"'])([^\"']{16,})([\"'])",
        )
        private val RAW_IMAGE_BASE64 = Regex(
            pattern = "(?i)(?<![a-z0-9+/_-])(?:iVBORw0KGgo|/9j/|_9j_)(?:[a-z0-9+/_=-]|\\r|\\n){48,}",
        )
    }
}

private fun safeFence(value: String, info: String): String {
    val longestRun = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    return "$fence$info\n$value\n$fence\n\n"
}

private fun boundedFence(value: String, info: String, maxBytes: Int): Pair<String, Boolean> {
    val complete = safeFence(value, info)
    if (utf8Size(complete) <= maxBytes) return complete to false
    val marker = "\n[围栏内容因导出上限被截断]"
    var low = 0
    var high = value.length
    var best = safeFence(marker.trimStart(), info)
    while (low <= high) {
        val middle = (low + high) ushr 1
        val candidate = safeFence(value.takeValidUtf16Prefix(middle).trimEnd() + marker, info)
        if (utf8Size(candidate) <= maxBytes) {
            best = candidate
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return best to true
}

private fun String.takeValidUtf16Prefix(length: Int): String {
    var end = length.coerceIn(0, this.length)
    if (end in 1 until this.length && this[end - 1].isHighSurrogate()) end--
    return substring(0, end)
}

private fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size
