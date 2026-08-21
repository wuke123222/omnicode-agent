package dev.omnicode.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.SemiDesignCodeLanguage
import dev.omnicode.service.SemiDesignImageToCodeOptions
import dev.omnicode.service.SemiDesignImageToCodeWorkflow
import dev.omnicode.service.SemiDesignPackageContext
import dev.omnicode.service.SemiDesignProjectPreflight
import dev.omnicode.service.SemiDesignStyleStrategy
import dev.omnicode.service.SemiDesignTargetKind
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Configuration and bounded preflight review for the dedicated image-to-Semi workflow. */
internal class SemiDesignImageToCodeDialog(
    project: Project,
    private val preflight: SemiDesignProjectPreflight,
    private val imageNames: List<String>,
    initialInstructions: String,
) : DialogWrapper(project, true) {
    private val packageSelector = ComboBox(preflight.packages.toTypedArray()).apply {
        renderer = valueRenderer { it.displayName }
        selectedIndex = preflight.selectedPackageIndex
    }
    private val componentName = JBTextField("GeneratedView").apply {
        emptyText.text = "例如 CampaignDashboard"
    }
    private val targetKind = ComboBox(SemiDesignTargetKind.entries.toTypedArray()).apply {
        renderer = valueRenderer { it.label }
        selectedItem = SemiDesignTargetKind.COMPONENT
    }
    private val language = ComboBox(SemiDesignCodeLanguage.entries.toTypedArray()).apply {
        renderer = valueRenderer { it.label }
    }
    private val semiPackage = ComboBox(SemiDesignImageToCodeWorkflow.supportedSemiPackages.toTypedArray())
    private val styleStrategy = ComboBox(SemiDesignStyleStrategy.entries.toTypedArray()).apply {
        renderer = valueRenderer { it.label }
        selectedItem = SemiDesignStyleStrategy.CSS_MODULE
    }
    private val targetPath = JBTextField().apply {
        emptyText.text = "项目相对路径，例如 web/src/components/GeneratedView.tsx"
    }
    private val addDependencies = JBCheckBox("缺少时更新 package.json（不会自动执行 install）", true)
    private val responsive = JBCheckBox("生成桌面 / 窄屏响应式布局", true)
    private val accessibility = JBCheckBox("补齐键盘、语义标签和可见焦点", true)
    private val instructions = JBTextArea(initialInstructions.take(MAX_INITIAL_INSTRUCTIONS)).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 5
        emptyText.text = "可选：说明交互、数据来源、需要保留的现有逻辑或像素级重点"
        border = JBUI.Borders.empty(6)
    }
    private val packageDetail = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private var resolvedOptions: SemiDesignImageToCodeOptions? = null
    private var lastSuggestedTarget = ""
    private var suppressTargetSuggestion = false

    val options: SemiDesignImageToCodeOptions?
        get() = resolvedOptions

    init {
        title = "Semi Design 图转码"
        setOKButtonText("开始转换")
        setCancelButtonText("取消")
        setResizable(true)
        language.selectedItem = if (selectedPackage().typeScript) {
            SemiDesignCodeLanguage.TYPESCRIPT
        } else {
            SemiDesignCodeLanguage.JAVASCRIPT
        }
        semiPackage.selectedItem = selectedPackage().recommendedSemiPackage
        updatePackageDetail()
        updateSuggestedTarget(force = true)
        installSuggestionListeners()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("前端包", packageSelector)
            .addComponent(packageDetail)
            .addLabeledComponent("输出类型", targetKind)
            .addLabeledComponent("组件名", componentName)
            .addLabeledComponent("代码语言", language)
            .addLabeledComponent("Semi React 包", semiPackage)
            .addLabeledComponent("样式策略", styleStrategy)
            .addLabeledComponent("主输出文件", targetPath)
            .addComponent(addDependencies)
            .addComponent(responsive)
            .addComponent(accessibility)
            .addLabeledComponent(
                "补充要求",
                JBScrollPane(instructions).apply {
                    preferredSize = Dimension(0, JBUI.scale(112))
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                },
            )
            .panel.apply { isOpaque = false }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 8, 4)
            add(workflowSummary())
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(form)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(600))
            isOpaque = true
            background = OmniCodeUiPalette.canvas
            add(JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                viewport.isOpaque = false
                isOpaque = false
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = JBUI.scale(16)
            }, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val selectedPackage = selectedPackage()
        val candidate = SemiDesignImageToCodeOptions(
            packageContext = selectedPackage,
            semiPackage = semiPackage.selectedItem as String,
            componentName = componentName.text.trim(),
            targetPath = targetPath.text.trim(),
            targetKind = selectedTargetKind(),
            language = selectedLanguage(),
            styleStrategy = selectedStyleStrategy(),
            addMissingDependencies = addDependencies.isSelected,
            responsive = responsive.isSelected,
            accessibility = accessibility.isSelected,
            additionalInstructions = instructions.text.trim(),
        )
        val error = runCatching {
            SemiDesignImageToCodeWorkflow.validateOptions(preflight.root, candidate)
        }.exceptionOrNull()
        if (error != null) {
            setErrorText(error.message ?: "请检查图转码配置", targetPath)
            return
        }
        resolvedOptions = candidate
        super.doOKAction()
    }

    private fun workflowSummary(): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
        add(JBLabel("图片 → 视觉结构 → Semi 组件 → 文件审阅").apply {
            font = JBFont.h3().asBold()
            foreground = OmniCodeUiPalette.primary
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(JBUI.scale(5)))
        add(JBLabel("参考图 ${imageNames.size} 张：${imageNames.joinToString("、") { it.take(48) }.take(220)}").apply {
            foreground = OmniCodeUiPalette.secondary
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(JBLabel("预检只读取有界 package.json；生成后的写文件、命令、审阅和回退仍使用 OmniCode 安全链路。").apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        if (preflight.issues.isNotEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(7)))
            add(JBTextArea(preflight.issues.joinToString("\n") { "• $it" }).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                foreground = OmniCodeUiPalette.warning
                font = JBFont.small()
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(64))
            })
        }
    }

    private fun installSuggestionListeners() {
        val listener = object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateSuggestedTarget(force = false)
            override fun removeUpdate(event: DocumentEvent) = updateSuggestedTarget(force = false)
            override fun changedUpdate(event: DocumentEvent) = updateSuggestedTarget(force = false)
        }
        componentName.document.addDocumentListener(listener)
        packageSelector.addActionListener {
            val context = selectedPackage()
            language.selectedItem = if (context.typeScript) {
                SemiDesignCodeLanguage.TYPESCRIPT
            } else {
                SemiDesignCodeLanguage.JAVASCRIPT
            }
            semiPackage.selectedItem = context.recommendedSemiPackage
            updatePackageDetail()
            updateSuggestedTarget(force = true)
        }
        targetKind.addActionListener { updateSuggestedTarget(force = false) }
        language.addActionListener { updateSuggestedTarget(force = false) }
        targetPath.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = noteManualTargetEdit()
            override fun removeUpdate(event: DocumentEvent) = noteManualTargetEdit()
            override fun changedUpdate(event: DocumentEvent) = noteManualTargetEdit()
        })
    }

    private fun noteManualTargetEdit() {
        if (!suppressTargetSuggestion && targetPath.text != lastSuggestedTarget) lastSuggestedTarget = ""
    }

    private fun updateSuggestedTarget(force: Boolean) {
        val current = targetPath.text.trim()
        if (!force && lastSuggestedTarget.isBlank() && current.isNotBlank()) return
        if (!force && current.isNotBlank() && current != lastSuggestedTarget) return
        val suggested = SemiDesignImageToCodeWorkflow.suggestedTargetPath(
            selectedPackage(),
            selectedTargetKind(),
            componentName.text.trim(),
            selectedLanguage(),
        )
        suppressTargetSuggestion = true
        try {
            targetPath.text = suggested
            lastSuggestedTarget = suggested
        } finally {
            suppressTargetSuggestion = false
        }
    }

    private fun updatePackageDetail() {
        val selected = selectedPackage()
        addDependencies.isEnabled = selected.packageJsonPresent
        if (!selected.packageJsonPresent) addDependencies.isSelected = false
        packageDetail.text = buildString {
            append(selected.packageJsonPath)
            append(" · ").append(selected.packageManager)
            append(" · 生成时使用 ").append(selected.recommendedSemiPackage)
            if (selected.installedSemiPackage != null &&
                selected.installedSemiPackage != selected.recommendedSemiPackage
            ) {
                append("（当前安装 ").append(selected.installedSemiPackage).append("）")
            }
        }.take(240)
    }

    private fun selectedPackage(): SemiDesignPackageContext =
        packageSelector.selectedItem as SemiDesignPackageContext

    private fun selectedTargetKind(): SemiDesignTargetKind = targetKind.selectedItem as SemiDesignTargetKind

    private fun selectedLanguage(): SemiDesignCodeLanguage = language.selectedItem as SemiDesignCodeLanguage

    private fun selectedStyleStrategy(): SemiDesignStyleStrategy =
        styleStrategy.selectedItem as SemiDesignStyleStrategy

    private companion object {
        const val MAX_INITIAL_INSTRUCTIONS = 4_000
    }
}

private fun <T> valueRenderer(label: (T) -> String): ListCellRenderer<T> {
    val delegate = DefaultListCellRenderer()
    return ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
        delegate.getListCellRendererComponent(
            list,
            value?.let(label).orEmpty(),
            index,
            isSelected,
            cellHasFocus,
        )
    }
}
