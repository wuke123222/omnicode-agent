package dev.omnicode.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.omnicode.agent.AgentMode
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.DailyUsageSummary
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.PersistenceRetention
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolExecutionQuery
import dev.omnicode.persistence.ToolExecutionRecord
import dev.omnicode.persistence.UsageQuery
import dev.omnicode.persistence.UsageRecord
import dev.omnicode.persistence.UsageSummary
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class OmniCodeInsightsConfigurable : SearchableConfigurable {
    private var view: InsightsView? = null

    override fun getId(): String = "dev.omnicode.insights"

    override fun getDisplayName(): String = "OmniCode 用量与历史"

    override fun createComponent(): JComponent {
        val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
        val current = InsightsView(
            OmniCodeLocalStore.default(
                PersistenceRetention(
                    maxConversations = platform.historyRetention,
                    usageRetentionDays = platform.usageRetentionDays,
                ),
            ),
        )
        current.pricing.reset(platform.pricing)
        current.refreshAll()
        view = current
        return current
    }

    override fun isModified(): Boolean {
        val current = view ?: return false
        return current.pricing.isModified(OmniCodePlatformSettingsService.getInstance().snapshot().pricing)
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val current = view ?: return
        current.pricing.stopEditing()
        val pricing = try {
            normalizePricingRows(current.pricing.rows())
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: "价格配置无效")
        }
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.pricing = pricing.mapTo(mutableListOf()) { price ->
                ModelPricingState().apply {
                    providerId = price.providerId
                    modelPattern = price.modelPattern
                    inputUsdPerMillion = price.inputUsdPerMillion
                    outputUsdPerMillion = price.outputUsdPerMillion
                }
            }
        }
        current.pricing.reset(OmniCodePlatformSettingsService.getInstance().snapshot().pricing)
    }

    override fun reset() {
        view?.pricing?.reset(OmniCodePlatformSettingsService.getInstance().snapshot().pricing)
    }

    override fun disposeUIResources() {
        view?.dispose()
        view = null
    }

    fun selectSection(index: Int) {
        view?.selectSection(index)
    }
}

internal class InsightsEmbeddedSettings : OmniCodeEmbeddedSettings {
    private val view: InsightsView

    override val component: JComponent get() = view
    override val isModified: Boolean
        get() = view.pricing.isModified(OmniCodePlatformSettingsService.getInstance().snapshot().pricing)

    init {
        val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
        view = InsightsView(
            OmniCodeLocalStore.default(
                PersistenceRetention(
                    maxConversations = platform.historyRetention,
                    usageRetentionDays = platform.usageRetentionDays,
                ),
            ),
            showTabs = false,
        )
        view.pricing.reset(platform.pricing)
        view.refreshAll()
    }

    override fun save() {
        view.pricing.stopEditing()
        val pricing = try {
            normalizePricingRows(view.pricing.rows())
        } catch (error: IllegalArgumentException) {
            throw OmniCodeSettingsSaveException(error.message ?: "价格配置无效。", error)
        }
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.pricing = pricing.mapTo(mutableListOf()) { price ->
                ModelPricingState().apply {
                    providerId = price.providerId
                    modelPattern = price.modelPattern
                    inputUsdPerMillion = price.inputUsdPerMillion
                    outputUsdPerMillion = price.outputUsdPerMillion
                }
            }
        }
        reset()
    }

    override fun reset() {
        view.pricing.reset(OmniCodePlatformSettingsService.getInstance().snapshot().pricing)
    }

    override fun selectSection(index: Int) {
        view.selectSection(index)
    }

    override fun dispose() {
        view.dispose()
    }
}

private class InsightsView(
    private val store: OmniCodeLocalStore,
    private val showTabs: Boolean = true,
) : JPanel(BorderLayout()), Disposable {
    val pricing = PricingPanel()

    private val disposed = AtomicBoolean(false)
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val usage = UsagePanel(::refreshUsage, ::clearUsage)
    private val history = HistoryPanel(::refreshHistory, ::deleteConversation)
    private val audit = ToolAuditPanel(::refreshAudit)
    private val sections = listOf(
        "使用统计" to usage,
        "历史记录" to history,
        "工具审计" to audit,
        "价格配置" to pricing,
    )
    private val cardsLayout = CardLayout()
    private val cards = JPanel(cardsLayout).apply {
        if (!showTabs) sections.forEachIndexed { index, section -> add(section.second, index.toString()) }
    }
    private val tabs = JBTabbedPane().apply {
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        if (showTabs) sections.forEach { section -> addTab(section.first, section.second) }
    }

    init {
        border = JBUI.Borders.empty(8)
        add(if (showTabs) tabs else cards, BorderLayout.CENTER)
    }

    fun refreshAll() {
        refreshUsage()
        refreshHistory()
        refreshAudit()
    }

    fun selectSection(index: Int) {
        if (index !in sections.indices) return
        if (showTabs) tabs.selectedIndex = index else cardsLayout.show(cards, index.toString())
    }

    override fun dispose() {
        disposed.set(true)
        generations.values.forEach { it.incrementAndGet() }
    }

    private fun refreshUsage() {
        usage.setLoading()
        background(
            key = "usage",
            load = ::loadUsageSnapshot,
            success = usage::show,
            failure = usage::showError,
        )
    }

    private fun clearUsage() {
        if (!confirm("清除所有已保留的用量记录？", "清除用量记录")) return
        usage.setLoading("正在清除…")
        background(
            key = "usage",
            load = {
                store.clearUsage()
                loadUsageSnapshot()
            },
            success = usage::show,
            failure = usage::showError,
        )
    }

    private fun loadUsageSnapshot(): UsageInsightsSnapshot {
        val zone = ZoneId.systemDefault()
        val query = UsageQuery(limit = Int.MAX_VALUE)
        return UsageInsightsSnapshot(
            summary = store.summarizeUsage(query, zone),
            rows = usageRowsByMode(store.queryUsage(query), zone),
        )
    }

    private fun refreshHistory() {
        history.setLoading()
        background(
            key = "history",
            load = { store.conversations(limit = Int.MAX_VALUE) },
            success = history::show,
            failure = history::showError,
        )
    }

    private fun deleteConversation(record: ConversationRecord) {
        if (!confirm("删除对话“${record.title}”？", "删除对话")) return
        history.setLoading("正在删除…")
        background(
            key = "history",
            load = {
                store.deleteConversation(record.id)
                store.conversations(limit = Int.MAX_VALUE)
            },
            success = history::show,
            failure = history::showError,
        )
    }

    private fun refreshAudit() {
        audit.setLoading()
        background(
            key = "audit",
            load = { store.queryToolExecutions(ToolExecutionQuery(limit = Int.MAX_VALUE)) },
            success = audit::show,
            failure = audit::showError,
        )
    }

    private fun confirm(message: String, title: String): Boolean =
        Messages.showYesNoDialog(
            null as Project?,
            message,
            title,
            "继续",
            "取消",
            Messages.getWarningIcon(),
        ) == Messages.YES

    private fun <T> background(
        key: String,
        load: () -> T,
        success: (T) -> Unit,
        failure: (String) -> Unit,
    ) {
        val generation = generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        AppExecutorUtil.getAppExecutorService().submit {
            val result = runCatching(load)
            val application = ApplicationManager.getApplication()
            application.invokeLater({
                if (disposed.get() || generations[key]?.get() != generation) return@invokeLater
                result.fold(
                    onSuccess = success,
                    onFailure = { error -> failure(safeError(error)) },
                )
            }, ModalityState.any())
        }
    }

    private fun safeError(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(240)?.ifBlank { null }
            ?: error::class.java.simpleName
}

private class UsagePanel(
    private val refresh: () -> Unit,
    private val clear: () -> Unit,
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val runs = Metric("运行次数")
    private val input = Metric("输入 Token")
    private val output = Metric("输出 Token")
    private val total = Metric("总 Token")
    private val cost = Metric("预估费用")
    private val status = JBLabel(" ")
    private val model = DailyUsageTableModel()
    private val table = insightsTable(model)
    private val trend = TokenTrendChart()

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                add(JBLabel("使用概览").apply {
                    font = font.deriveFont(Font.BOLD, font.size2D + 2f)
                })
                add(JBLabel("统计所有项目、供应商和模型的本地保留记录。").apply {
                    foreground = UIManager.getColor("Label.disabledForeground")
                })
            }, BorderLayout.CENTER)
            add(actionRow(
                JButton("刷新").apply { addActionListener { refresh() } },
                JButton("清除…").apply { addActionListener { clear() } },
            ), BorderLayout.EAST)
        }, BorderLayout.NORTH)

        add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            isOpaque = false
            add(JPanel(BorderLayout(0, JBUI.scale(10))).apply {
                isOpaque = false
                add(JPanel(GridLayout(1, 5, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    listOf(runs, input, output, total, cost).forEach { add(it) }
                }, BorderLayout.NORTH)
                add(UsageCardPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    add(JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(JBLabel("Token 趋势").apply {
                            font = font.deriveFont(Font.BOLD)
                        }, BorderLayout.WEST)
                        add(JBLabel("近 30 天 · 输入与输出").apply {
                            foreground = UIManager.getColor("Label.disabledForeground")
                        }, BorderLayout.EAST)
                    }, BorderLayout.NORTH)
                    add(trend, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                preferredSize = Dimension(0, JBUI.scale(255))
            }, BorderLayout.NORTH)

            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(JBLabel("每日明细").apply {
                    font = font.deriveFont(Font.BOLD)
                }, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    fun setLoading(message: String = "正在加载用量…") {
        status.text = message
    }

    fun show(snapshot: UsageInsightsSnapshot) {
        val summary = snapshot.summary
        runs.value(summary.runCount.toString())
        input.value(formatInteger(summary.inputTokens))
        output.value(formatInteger(summary.outputTokens))
        total.value(formatInteger(summary.totalTokens))
        cost.value(formatUsd(summary.estimatedCostUsd))
        cost.toolTipText = if (summary.pricedRunCount == summary.runCount) {
            "所有运行都已配置价格"
        } else {
            "${summary.runCount} 次运行中有 ${summary.pricedRunCount} 次可估算费用"
        }
        model.setRows(snapshot.rows)
        trend.setRows(summary.daily)
        status.text = "${summary.daily.size} 天 · ${snapshot.rows.size} 个模式分组 · ${summary.pricedRunCount} 次已估价运行"
    }

    fun showError(message: String) {
        status.text = "用量加载失败：$message"
    }
}

internal data class UsageInsightsSnapshot(
    val summary: UsageSummary,
    val rows: List<ModeDailyUsageRow>,
)

internal data class ModeDailyUsageRow(
    val date: LocalDate,
    val mode: AgentMode?,
    val runCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

internal fun usageRowsByMode(
    records: List<UsageRecord>,
    zoneId: ZoneId,
): List<ModeDailyUsageRow> = records
    .groupBy { record -> record.recordedAt.atZone(zoneId).toLocalDate() to record.mode }
    .map { (key, grouped) ->
        val costs = grouped.mapNotNull(UsageRecord::estimatedCostUsd)
        ModeDailyUsageRow(
            date = key.first,
            mode = key.second,
            runCount = grouped.size,
            inputTokens = grouped.sumOf(UsageRecord::inputTokens),
            outputTokens = grouped.sumOf(UsageRecord::outputTokens),
            estimatedCostUsd = costs.takeIf(List<BigDecimal>::isNotEmpty)
                ?.fold(BigDecimal.ZERO, BigDecimal::add),
        )
    }
    .sortedWith(compareBy<ModeDailyUsageRow>({ it.date }, { modeSortOrder(it.mode) }))

internal fun displayAgentMode(mode: AgentMode?): String = when (mode) {
    AgentMode.AGENT -> "Agent"
    AgentMode.PLAN -> "Plan 看板"
    AgentMode.CLAUDE_PLAN -> "Claude Plan"
    AgentMode.RESEARCH -> "Research"
    null -> "—"
}

private fun modeSortOrder(mode: AgentMode?): Int = when (mode) {
    AgentMode.AGENT -> 0
    AgentMode.PLAN -> 1
    AgentMode.CLAUDE_PLAN -> 2
    AgentMode.RESEARCH -> 3
    null -> 4
}

private class HistoryPanel(
    private val refresh: () -> Unit,
    private val delete: (ConversationRecord) -> Unit,
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val model = ConversationTableModel()
    private val table = insightsTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val summary = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }
    private val status = JBLabel(" ")
    private val deleteButton = JButton("删除…").apply { isEnabled = false }

    init {
        border = JBUI.Borders.empty(8)
        add(actionRow(
            JButton("刷新").apply { addActionListener { refresh() } },
            JButton("查看摘要").apply { addActionListener { showSelectedSummary() } },
            deleteButton.apply { addActionListener { selectedRecord()?.let(delete) } },
        ), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), JBScrollPane(summary)).apply {
            resizeWeight = 0.66
            dividerSize = JBUI.scale(6)
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        table.selectionModel.addListSelectionListener {
            deleteButton.isEnabled = selectedRecord() != null
            if (!it.valueIsAdjusting) showSelectedSummary()
        }
    }

    fun setLoading(message: String = "正在加载历史记录…") {
        status.text = message
    }

    fun show(records: List<ConversationRecord>) {
        model.setRows(records)
        table.clearSelection()
        summary.text = ""
        deleteButton.isEnabled = false
        status.text = "已保留 ${records.size} 个对话"
    }

    fun showError(message: String) {
        status.text = "历史记录加载失败：$message"
    }

    private fun showSelectedSummary() {
        summary.text = selectedRecord()?.let(::conversationSummary).orEmpty()
        summary.caretPosition = 0
    }

    private fun selectedRecord(): ConversationRecord? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return model.row(table.convertRowIndexToModel(viewRow))
    }
}

private class ToolAuditPanel(
    private val refresh: () -> Unit,
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val model = ToolAuditTableModel()
    private val status = JBLabel(" ")

    init {
        border = JBUI.Borders.empty(8)
        add(actionRow(JButton("刷新").apply { addActionListener { refresh() } }), BorderLayout.NORTH)
        add(JBScrollPane(insightsTable(model)), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    fun setLoading(message: String = "正在加载工具审计…") {
        status.text = message
    }

    fun show(records: List<ToolExecutionRecord>) {
        model.setRows(records)
        status.text = "已保留 ${records.size} 条审计记录"
    }

    fun showError(message: String) {
        status.text = "工具审计加载失败：$message"
    }
}

internal data class PricingRow(
    val providerGlob: String,
    val modelGlob: String,
    val inputUsdPerMillion: String,
    val outputUsdPerMillion: String,
)

internal class PricingPanel : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val model = PricingTableModel()
    private val table = insightsTable(model)

    init {
        border = JBUI.Borders.empty(8)
        val addButton = JButton("添加").apply {
            addActionListener {
                this@PricingPanel.model.add(PricingRow("*", "*", "0", "0"))
                val row = this@PricingPanel.model.rowCount - 1
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row)
                    table.scrollRectToVisible(table.getCellRect(row, 0, true))
                    table.editCellAt(row, 0)
                    table.editorComponent?.requestFocusInWindow()
                }
            }
        }
        val removeButton = JButton("删除").apply {
            addActionListener {
                val selected = table.selectedRows
                    .map(table::convertRowIndexToModel)
                    .sortedDescending()
                selected.forEach(this@PricingPanel.model::remove)
            }
        }
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("配置每百万 Token 的预估美元价格；供应商和模型字段支持通配符。"), BorderLayout.CENTER)
            add(actionRow(addButton, removeButton), BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun rows(): List<PricingRow> = model.rows()

    fun reset(pricing: List<ModelPricing>) {
        stopEditing()
        model.setRows(pricing.map { price ->
            PricingRow(
                providerGlob = price.providerId,
                modelGlob = price.modelPattern,
                inputUsdPerMillion = decimal(price.inputUsdPerMillion),
                outputUsdPerMillion = decimal(price.outputUsdPerMillion),
            )
        })
    }

    fun isModified(pricing: List<ModelPricing>): Boolean {
        stopEditing()
        val edited = runCatching { normalizePricingRows(rows()) }.getOrNull() ?: return true
        return edited != pricing
    }

    fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }
}

internal fun normalizePricingRows(rows: List<PricingRow>): List<ModelPricing> {
    val normalized = rows.mapIndexed { index, row ->
        val provider = row.providerGlob.trim().ifBlank {
            throw IllegalArgumentException("Pricing row ${index + 1}: provider glob is required")
        }
        val model = row.modelGlob.trim().ifBlank { "*" }
        val input = parseRate(row.inputUsdPerMillion, index, "input")
        val output = parseRate(row.outputUsdPerMillion, index, "output")
        ModelPricing(provider, model, input, output)
    }
    val duplicate = normalized.groupingBy { it.providerId to it.modelPattern }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
    require(duplicate == null) {
        "Duplicate pricing rule: ${duplicate?.key?.first} / ${duplicate?.key?.second}"
    }
    return normalized
}

private fun parseRate(value: String, index: Int, label: String): Double {
    val parsed = value.trim().toDoubleOrNull()
        ?: throw IllegalArgumentException("Pricing row ${index + 1}: $label rate must be a number")
    require(parsed.isFinite() && parsed >= 0.0) {
        "Pricing row ${index + 1}: $label rate must be finite and non-negative"
    }
    return parsed
}

private open class UsageCardPanel(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12)
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val inset = JBUI.scale(1).toFloat() / 2f
            val arc = JBUI.scale(10).toFloat()
            copy.color = usageSurfaceColor()
            copy.fillRoundRect(
                inset.toInt(),
                inset.toInt(),
                (width - inset * 2).toInt().coerceAtLeast(0),
                (height - inset * 2).toInt().coerceAtLeast(0),
                arc.toInt(),
                arc.toInt(),
            )
            copy.color = usageBorderColor()
            copy.stroke = BasicStroke(JBUI.scale(1).toFloat())
            copy.drawRoundRect(
                inset.toInt(),
                inset.toInt(),
                (width - inset * 2 - 1).toInt().coerceAtLeast(0),
                (height - inset * 2 - 1).toInt().coerceAtLeast(0),
                arc.toInt(),
                arc.toInt(),
            )
        } finally {
            copy.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class Metric(title: String) : UsageCardPanel(BorderLayout(0, JBUI.scale(4))) {
    private val value = JBLabel("0").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 5f)
    }

    init {
        add(JBLabel(title).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
        }, BorderLayout.NORTH)
        add(value, BorderLayout.CENTER)
        minimumSize = Dimension(JBUI.scale(96), JBUI.scale(68))
    }

    fun value(text: String) {
        value.text = text
    }
}

private data class TokenTrendPoint(
    val date: LocalDate,
    val inputTokens: Long,
    val outputTokens: Long,
)

private class TokenTrendChart : JComponent() {
    private var points: List<TokenTrendPoint> = lastThirtyDays(emptyList())

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(480), JBUI.scale(145))
        minimumSize = Dimension(JBUI.scale(240), JBUI.scale(110))
    }

    fun setRows(rows: List<DailyUsageSummary>) {
        points = lastThirtyDays(rows)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (width <= 0 || height <= 0) return

        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val plotLeft = JBUI.scale(52)
            val plotRight = width - JBUI.scale(12)
            val plotTop = JBUI.scale(27)
            val plotBottom = height - JBUI.scale(23)
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1)
            val labelColor = UIManager.getColor("Label.disabledForeground")
                ?: UIManager.getColor("Label.foreground")
                ?: Color.GRAY
            val inputColor = inputTokenColor()
            val outputColor = outputTokenColor()

            drawLegend(copy, plotLeft, JBUI.scale(13), inputColor, "输入")
            drawLegend(copy, plotLeft + JBUI.scale(72), JBUI.scale(13), outputColor, "输出")

            val peak = points.maxOfOrNull { maxOf(it.inputTokens, it.outputTokens) } ?: 0L
            val chartMax = chartCeiling(peak)
            copy.font = copy.font.deriveFont(copy.font.size2D - 1f)
            repeat(3) { index ->
                val ratio = index / 2.0
                val y = plotBottom - (plotHeight * ratio).toInt()
                copy.color = withAlpha(usageBorderColor(), 145)
                copy.stroke = BasicStroke(JBUI.scale(1).toFloat())
                copy.drawLine(plotLeft, y, plotRight, y)
                val value = (chartMax * ratio).toLong()
                val label = formatCompactInteger(value)
                copy.color = labelColor
                copy.drawString(label, plotLeft - copy.fontMetrics.stringWidth(label) - JBUI.scale(7), y + JBUI.scale(4))
            }

            if (peak <= 0L) {
                val message = "近 30 天暂无 Token 用量"
                copy.color = labelColor
                copy.font = copy.font.deriveFont(Font.PLAIN, copy.font.size2D + 1f)
                copy.drawString(
                    message,
                    plotLeft + (plotWidth - copy.fontMetrics.stringWidth(message)) / 2,
                    plotTop + plotHeight / 2,
                )
                drawDateLabels(copy, plotLeft, plotRight, plotBottom, labelColor)
                return
            }

            drawSeries(copy, points.map(TokenTrendPoint::inputTokens), inputColor, chartMax, plotLeft, plotTop, plotWidth, plotHeight)
            drawSeries(copy, points.map(TokenTrendPoint::outputTokens), outputColor, chartMax, plotLeft, plotTop, plotWidth, plotHeight)
            drawDateLabels(copy, plotLeft, plotRight, plotBottom, labelColor)
        } finally {
            copy.dispose()
        }
    }

    private fun drawSeries(
        graphics: Graphics2D,
        values: List<Long>,
        color: Color,
        chartMax: Long,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        if (values.isEmpty()) return
        val path = Path2D.Double()
        values.forEachIndexed { index, value ->
            val x = left + width * index.toDouble() / (values.size - 1).coerceAtLeast(1)
            val y = top + height * (1.0 - value.toDouble() / chartMax.coerceAtLeast(1L))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        graphics.color = color
        graphics.stroke = BasicStroke(
            JBUI.scale(2).toFloat(),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        graphics.draw(path)

        val lastX = left + width
        val lastValue = values.last()
        val lastY = top + height * (1.0 - lastValue.toDouble() / chartMax.coerceAtLeast(1L))
        val radius = JBUI.scale(3)
        graphics.fillOval(lastX - radius, lastY.toInt() - radius, radius * 2, radius * 2)
    }

    private fun drawLegend(graphics: Graphics2D, x: Int, y: Int, color: Color, label: String) {
        graphics.color = color
        graphics.fillRoundRect(x, y - JBUI.scale(5), JBUI.scale(16), JBUI.scale(3), JBUI.scale(3), JBUI.scale(3))
        graphics.color = UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY
        graphics.drawString(label, x + JBUI.scale(21), y)
    }

    private fun drawDateLabels(graphics: Graphics2D, left: Int, right: Int, bottom: Int, color: Color) {
        if (points.isEmpty()) return
        val labels = listOf(
            left to points.first().date,
            (left + right) / 2 to points[points.lastIndex / 2].date,
            right to points.last().date,
        )
        graphics.color = color
        labels.forEachIndexed { index, (x, date) ->
            val text = CHART_DATE_FORMAT.format(date)
            val textX = when (index) {
                0 -> x
                labels.lastIndex -> x - graphics.fontMetrics.stringWidth(text)
                else -> x - graphics.fontMetrics.stringWidth(text) / 2
            }
            graphics.drawString(text, textX, bottom + JBUI.scale(17))
        }
    }
}

internal class DailyUsageTableModel : AbstractTableModel() {
    private var rows: List<ModeDailyUsageRow> = emptyList()
    private val columns = arrayOf("日期", "模式", "运行次数", "输入", "输出", "总计", "预估费用")

    fun setRows(value: List<ModeDailyUsageRow>) {
        rows = value
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        2, 3, 4, 5 -> java.lang.Long::class.java
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.date.toString()
            1 -> displayAgentMode(row.mode)
            2 -> row.runCount.toLong()
            3 -> row.inputTokens
            4 -> row.outputTokens
            5 -> row.totalTokens
            else -> formatUsd(row.estimatedCostUsd)
        }
    }
}

private class ConversationTableModel : AbstractTableModel() {
    private var rows: List<ConversationRecord> = emptyList()
    private val columns = arrayOf("标题", "项目", "更新时间", "消息数")

    fun setRows(value: List<ConversationRecord>) {
        rows = value
        fireTableDataChanged()
    }

    fun row(index: Int): ConversationRecord? = rows.getOrNull(index)

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 3) java.lang.Integer::class.java else String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.title.ifBlank { "未命名对话" }
            1 -> row.projectId
            2 -> formatInstant(row.updatedAt)
            else -> row.messages.size
        }
    }
}

internal class ToolAuditTableModel : AbstractTableModel() {
    private var rows: List<ToolExecutionRecord> = emptyList()
    private val columns = arrayOf("时间", "工具", "模式", "状态", "审批", "耗时")

    fun setRows(value: List<ToolExecutionRecord>) {
        rows = value
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> formatInstant(row.recordedAt)
            1 -> row.toolName
            2 -> displayAgentMode(row.mode)
            3 -> row.status.name.lowercase().replaceFirstChar(Char::uppercase)
            4 -> row.approvalDecision.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
            else -> formatDuration(row.durationMillis)
        }
    }
}

private class PricingTableModel : AbstractTableModel() {
    private val rows = mutableListOf<PricingRow>()
    private val columns = arrayOf("供应商通配", "模型通配", "输入 USD / 1M", "输出 USD / 1M")

    fun setRows(value: List<PricingRow>) {
        rows.clear()
        rows.addAll(value)
        fireTableDataChanged()
    }

    fun rows(): List<PricingRow> = rows.toList()

    fun add(row: PricingRow) {
        rows += row
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun remove(index: Int) {
        if (index !in rows.indices) return
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
        0 -> rows[rowIndex].providerGlob
        1 -> rows[rowIndex].modelGlob
        2 -> rows[rowIndex].inputUsdPerMillion
        else -> rows[rowIndex].outputUsdPerMillion
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val current = rows[rowIndex]
        val text = value?.toString().orEmpty()
        rows[rowIndex] = when (columnIndex) {
            0 -> current.copy(providerGlob = text)
            1 -> current.copy(modelGlob = text)
            2 -> current.copy(inputUsdPerMillion = text)
            else -> current.copy(outputUsdPerMillion = text)
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }
}

internal fun conversationSummary(record: ConversationRecord): String = buildString {
    appendLine(record.title.ifBlank { "未命名对话" })
    appendLine("项目：${record.projectId}")
    appendLine("创建时间：${formatInstant(record.createdAt)}")
    appendLine("更新时间：${formatInstant(record.updatedAt)}")
    appendLine("消息数：${record.messages.size}")
    val counts = record.messages.groupingBy(MessageSnapshot::role).eachCount()
    appendLine(SnapshotRole.entries.joinToString(" · ") { role ->
        "${role.name.lowercase()}: ${counts[role] ?: 0}"
    })
    appendLine()
    record.messages.takeLast(12).forEach { message ->
        val tool = message.toolName?.let { " · $it" }.orEmpty()
        appendLine("${message.role.name.lowercase()}$tool${if (message.isError) " · error" else ""}")
        appendLine(message.text.replace('\n', ' ').take(500))
        appendLine()
    }
}

private fun <T : AbstractTableModel> insightsTable(model: T): JBTable = JBTable(model).apply {
    autoCreateRowSorter = true
    fillsViewportHeight = true
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    rowHeight = JBUI.scale(24)
    tableHeader.reorderingAllowed = false
    setDefaultRenderer(Number::class.java, object : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.RIGHT
        }
    })
}

private fun actionRow(vararg components: JComponent): JPanel =
    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        components.forEach { add(it) }
    }

private fun lastThirtyDays(rows: List<DailyUsageSummary>): List<TokenTrendPoint> {
    val byDate = rows.associateBy(DailyUsageSummary::date)
    val today = LocalDate.now()
    return (29 downTo 0).map { daysAgo ->
        val date = today.minusDays(daysAgo.toLong())
        val row = byDate[date]
        TokenTrendPoint(
            date = date,
            inputTokens = row?.inputTokens ?: 0L,
            outputTokens = row?.outputTokens ?: 0L,
        )
    }
}

private fun chartCeiling(value: Long): Long {
    if (value <= 0L) return 1L
    var magnitude = 1L
    while (value / magnitude >= 10L && magnitude <= Long.MAX_VALUE / 10L) {
        magnitude *= 10L
    }
    val units = value / magnitude + if (value % magnitude == 0L) 0L else 1L
    return if (units > Long.MAX_VALUE / magnitude) Long.MAX_VALUE else units * magnitude
}

private fun formatCompactInteger(value: Long): String = when {
    value >= 1_000_000_000L -> compactNumber(value, 1_000_000_000L, "B")
    value >= 1_000_000L -> compactNumber(value, 1_000_000L, "M")
    value >= 1_000L -> compactNumber(value, 1_000L, "K")
    else -> value.toString()
}

private fun compactNumber(value: Long, divisor: Long, suffix: String): String {
    val scaled = value.toDouble() / divisor
    val number = if (scaled >= 10.0 || scaled % 1.0 == 0.0) {
        String.format("%.0f", scaled)
    } else {
        String.format("%.1f", scaled)
    }
    return "$number$suffix"
}

private fun usageSurfaceColor(): Color =
    UIManager.getColor("TextField.background")
        ?: UIManager.getColor("Panel.background")
        ?: Color(0xF7F8FA)

private fun usageBorderColor(): Color =
    UIManager.getColor("Component.borderColor")
        ?: UIManager.getColor("Separator.foreground")
        ?: if (isDarkUsageTheme()) Color(0x55585F) else Color(0xD5D7DC)

private fun inputTokenColor(): Color =
    if (isDarkUsageTheme()) Color(0x6EA8FE) else Color(0x2864DC)

private fun outputTokenColor(): Color =
    if (isDarkUsageTheme()) Color(0xC084FC) else Color(0x7C3AED)

private fun isDarkUsageTheme(): Boolean {
    val background = UIManager.getColor("Panel.background") ?: return false
    val luminance = background.red * 0.299 + background.green * 0.587 + background.blue * 0.114
    return luminance < 128.0
}

private fun withAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private val NUMBER_FORMAT: NumberFormat = NumberFormat.getIntegerInstance()
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private val CHART_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun formatInteger(value: Long): String = NUMBER_FORMAT.format(value)

private fun formatUsd(value: BigDecimal?): String =
    value?.stripTrailingZeros()?.toPlainString()?.let { "\$$it" } ?: "—"

private fun formatInstant(value: java.time.Instant): String = TIME_FORMAT.format(value)

private fun formatDuration(value: Long?): String = when {
    value == null -> "—"
    value < 1_000 -> "$value ms"
    else -> String.format("%.2f s", value / 1_000.0)
}

private fun decimal(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
