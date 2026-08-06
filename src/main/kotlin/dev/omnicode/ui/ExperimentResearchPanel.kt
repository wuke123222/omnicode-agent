package dev.omnicode.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.mcp.ResearchConnectorCatalog
import dev.omnicode.mcp.ResearchAccess
import dev.omnicode.service.ExperimentDefinition
import dev.omnicode.service.ExperimentLabService
import dev.omnicode.service.averageLatencyMillis
import dev.omnicode.service.successRate
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/** Project-local A/B experiment and research connector workspace. No network request is made here. */
internal class ExperimentResearchPanel(
    private val project: Project,
    private val openMcpSettings: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val experiments = ExperimentLabService.getInstance(project)
    private val experimentList = JPanel()
    private val sourceList = JPanel()
    private val nameField = JTextField()
    private val hypothesisField = JTextField()
    private val variantsField = JTextField("对照,实验")
    private val sourceSearch = JTextField()

    init {
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty(14)
        add(header(), BorderLayout.NORTH)
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.add(experimentSection())
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        content.add(researchSection())
        add(JBScrollPane(content).apply { border = null; viewport.isOpaque = false }, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        experimentList.removeAll()
        experiments.list().forEach { experimentList.add(experimentCard(it)) }
        if (experiments.list().isEmpty()) experimentList.add(JBLabel("还没有实验。创建一个假设并用稳定分流验证它。"))
        sourceList.removeAll()
        ResearchConnectorCatalog.search(sourceSearch.text).forEach { source ->
            sourceList.add(sourceCard(source.name, source.provider, source.access.label, source.capabilities.joinToString(" · "), source.notes))
        }
        experimentList.revalidate(); experimentList.repaint(); sourceList.revalidate(); sourceList.repaint()
    }

    private fun header() = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel("实验与科研").apply { font = JBFont.h2().asBold(); foreground = OmniCodeUiPalette.primary }, BorderLayout.WEST)
        add(JBLabel("稳定分流、可复现实验、合规科研来源").apply { foreground = OmniCodeUiPalette.secondary }, BorderLayout.SOUTH)
    }

    private fun experimentSection(): JComponent = section("A/B Test 实验室", "分流键只保存哈希分配和计数，不保存提示词或模型输出。", experimentList).also { panel ->
        val form = JPanel(GridBagLayout()).apply { isOpaque = false }
        fun addField(label: String, field: JTextField, row: Int) {
            val c = GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(3, 0, 3, 8) }
            form.add(JBLabel(label), c)
            c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
            form.add(field, c)
        }
        addField("实验名称", nameField, 0); addField("假设", hypothesisField, 1); addField("变体（逗号分隔）", variantsField, 2)
        val create = JButton("创建实验").apply { addActionListener {
            runCatching { experiments.create(nameField.text, hypothesisField.text, variantsField.text.split(',').map(String::trim)); nameField.text = ""; hypothesisField.text = ""; refresh() }
                .onFailure { JOptionPane.showMessageDialog(this, it.message ?: "实验创建失败", "无法创建", JOptionPane.WARNING_MESSAGE) }
        } }
        val c = GridBagConstraints().apply { gridx = 1; gridy = 3; anchor = GridBagConstraints.WEST; insets = Insets(6, 0, 6, 0) }
        form.add(create, c)
        panel.add(form, 1)
    }

    private fun researchSection(): JComponent = section("科研连接器目录", "Science、Nature、知网通常需要机构或用户授权；此处只提供来源模板，不绕过登录、验证码或付费墙。", sourceList).also { panel ->
        val search = JPanel(BorderLayout(8, 0)).apply { isOpaque = false; add(sourceSearch, BorderLayout.CENTER); add(JButton("筛选").apply { addActionListener { refresh() } }, BorderLayout.EAST) }
        sourceSearch.toolTipText = "搜索 DOI、文献、中文文献、引用等能力"
        panel.add(search, 1)
    }

    private fun section(title: String, subtitle: String, list: JPanel): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = true; background = OmniCodeUiPalette.surface; border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(OmniCodeUiPalette.border), JBUI.Borders.empty(12))
        add(JBLabel(title).apply { font = JBFont.h3().asBold(); foreground = OmniCodeUiPalette.primary })
        add(JBLabel(subtitle).apply { foreground = OmniCodeUiPalette.secondary; border = JBUI.Borders.emptyBottom(8) })
        add(list.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false })
    }

    private fun experimentCard(experiment: ExperimentDefinition) = JPanel(BorderLayout(8, 4)).apply {
        isOpaque = true; background = OmniCodeUiPalette.control; border = JBUI.Borders.empty(8)
        val summary = experiment.variants.joinToString("  ·  ") { variant ->
            val observation = experiment.observations[variant.id]
            "${variant.label}: ${observation?.successRate?.let { "%.0f%%".format(it * 100) } ?: "—"} / ${observation?.averageLatencyMillis ?: 0}ms"
        }
        add(JBLabel("${experiment.name}  ${if (experiment.active) "· 运行中" else "· 草稿"}").apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
        add(JBLabel("${experiment.hypothesis}  |  $summary"), BorderLayout.CENTER)
        val actions = JPanel().apply {
            isOpaque = false
            add(JButton(if (experiment.active) "暂停" else "启用").apply { addActionListener { experiments.setActive(experiment.id, !experiment.active); refresh() } })
            add(JButton("记录样本").apply {
                isEnabled = experiment.active
                addActionListener { recordSample(experiment) }
            })
        }
        add(actions, BorderLayout.EAST)
    }

    private fun recordSample(experiment: ExperimentDefinition) {
        val subject = JOptionPane.showInputDialog(this, "输入脱敏 subject key（不会上传）", "记录实验样本", JOptionPane.PLAIN_MESSAGE)
            ?.trim().orEmpty()
        if (subject.isBlank()) return
        val success = JOptionPane.showConfirmDialog(this, "本次结果是否成功？", "记录实验样本", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
        val latency = JOptionPane.showInputDialog(this, "耗时毫秒（可填 0）", "记录实验样本", JOptionPane.PLAIN_MESSAGE)?.toLongOrNull() ?: 0L
        runCatching { experiments.record(experiment.id, subject, success, latency, 0, 0); refresh() }
            .onFailure { JOptionPane.showMessageDialog(this, it.message ?: "样本记录失败", "无法记录", JOptionPane.WARNING_MESSAGE) }
    }

    private fun sourceCard(name: String, provider: String, access: String, capabilities: String, notes: String) = JPanel(BorderLayout(8, 2)).apply {
        isOpaque = true; background = OmniCodeUiPalette.control; border = JBUI.Borders.empty(8)
        add(JBLabel("$name  ·  $provider").apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
        add(JBLabel("$access  |  $capabilities  |  $notes"), BorderLayout.CENTER)
        add(JButton("配置 MCP").apply {
            toolTipText = "打开 MCP 设置，由用户提供已授权的 endpoint"
            addActionListener { openMcpSettings() }
        }, BorderLayout.EAST)
    }
}
