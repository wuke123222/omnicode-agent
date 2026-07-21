package dev.omnicode.ui.workshop

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.workshop.ResolvedWorkshopSelection
import dev.omnicode.workshop.WorkshopCatalog
import dev.omnicode.workshop.WorkshopPet
import dev.omnicode.workshop.WorkshopSelection
import dev.omnicode.workshop.WorkshopSettingsService
import dev.omnicode.workshop.WorkshopTheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * Curated, local-only creative workshop. Catalog entries are compiled data; this page never loads
 * scripts, classes, remote themes, or executable pet behaviour.
 */
internal class CreativeWorkshopPanel(
    private val onSelectionChanged: (ResolvedWorkshopSelection) -> Unit,
    private val settings: WorkshopSettingsService = WorkshopSettingsService.getInstance(),
) : JPanel(BorderLayout()), Disposable {
    private val themeButtons = linkedMapOf<String, WorkshopChoiceButton>()
    private val petButtons = linkedMapOf<String, WorkshopChoiceButton>()
    private val themedSurfaces = mutableListOf<JPanel>()
    private val petEnabled = JCheckBox("在聊天工作台显示桌宠")
    private val previewPet = DesktopPetPanel(initiallyEnabled = true)
    private val previewTitle = JBLabel()
    private val previewDescription = JBLabel()
    private val status = JBLabel("选择会立即保存并应用到当前工作台").apply { font = JBFont.small() }
    private val scrollContent = JPanel()
    private val scroll = JBScrollPane(scrollContent)
    private var selection: WorkshopSelection = settings.snapshot()
    private var applyingSelection = false

    init {
        isOpaque = true
        border = JBUI.Borders.empty()
        scrollContent.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(18, 20, 24, 20)
            add(buildHero())
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(sectionTitle("工作台皮肤", "只改变 OmniCode 工作台，不修改 IDE 全局主题"))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildThemeGrid())
            add(Box.createVerticalStrut(JBUI.scale(18)))
            add(sectionTitle("桌宠伙伴", "跟随前台 Agent 的思考、工具、成功与失败状态"))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildPetGrid())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(buildPetControls())
            add(Box.createVerticalStrut(JBUI.scale(14)))
            add(buildPreview())
        }
        scroll.apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }
        add(scroll, BorderLayout.CENTER)
        renderSelection(settings.snapshot(), persist = false)
    }

    override fun dispose() {
        previewPet.dispose()
    }

    internal fun refreshFromSettings() {
        renderSelection(settings.snapshot(), persist = false)
    }

    private fun buildHero(): JComponent = themedSurface(BorderLayout(JBUI.scale(16), 0)).apply {
        border = JBUI.Borders.empty(16)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("创意工坊").apply {
                font = JBFont.h2().asBold()
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(JBLabel("给编码空间换肤，并选择一只会回应 Agent 状态的桌宠。").apply {
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(JBLabel("安全设计：主题与桌宠均为纯数据，不执行第三方脚本或命令。").apply {
                font = JBFont.small()
                alignmentX = LEFT_ALIGNMENT
            })
        }, BorderLayout.CENTER)
        add(JBLabel("✦").apply {
            font = font.deriveFont(28f)
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(JBUI.scale(52), JBUI.scale(52))
        }, BorderLayout.EAST)
    }

    private fun buildThemeGrid(): JComponent {
        val group = ButtonGroup()
        return WorkshopChoiceGridPanel().apply {
            isOpaque = false
            WorkshopCatalog.themes.forEach { theme ->
                val button = themeButton(theme)
                themeButtons[theme.id] = button
                group.add(button)
                add(button)
            }
        }
    }

    private fun buildPetGrid(): JComponent {
        val group = ButtonGroup()
        return WorkshopChoiceGridPanel().apply {
            isOpaque = false
            WorkshopCatalog.pets.forEach { pet ->
                val button = petButton(pet)
                petButtons[pet.id] = button
                group.add(button)
                add(button)
            }
        }
    }

    private fun buildPetControls(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
        petEnabled.addActionListener {
            if (!applyingSelection) renderSelection(selection.copy(petEnabled = petEnabled.isSelected), persist = true)
        }
        add(petEnabled, BorderLayout.WEST)
        add(JButton("恢复默认").apply {
            toolTipText = "恢复默认皮肤，并关闭桌宠"
            addActionListener { renderSelection(WorkshopCatalog.defaultSelection(), persist = true) }
        }, BorderLayout.EAST)
    }

    private fun buildPreview(): JComponent = themedSurface(BorderLayout(JBUI.scale(14), 0)).apply {
        border = JBUI.Borders.empty(12, 16)
        add(previewPet, BorderLayout.WEST)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(previewTitle.apply {
                font = JBFont.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(previewDescription.apply {
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(status.apply { alignmentX = LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
    }

    private fun sectionTitle(title: String, description: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(44))
        add(JBLabel(title).apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
        add(JBLabel(description).apply { font = JBFont.small() }, BorderLayout.SOUTH)
    }

    private fun themeButton(theme: WorkshopTheme): WorkshopChoiceButton {
        val colors = theme.palette.toUiColors()
        return WorkshopChoiceButton(
            title = theme.displayName,
            description = theme.description,
            icon = ThemeSwatchIcon(colors.background, colors.surface, colors.accent),
        ).apply {
            addActionListener {
                if (!applyingSelection) renderSelection(selection.copy(themeId = theme.id), persist = true)
            }
        }
    }

    private fun petButton(pet: WorkshopPet): WorkshopChoiceButton = WorkshopChoiceButton(
        title = "${pet.glyph}  ${pet.displayName}",
        description = pet.description,
        icon = null,
    ).apply {
        addActionListener {
            if (!applyingSelection) renderSelection(selection.copy(petId = pet.id), persist = true)
        }
    }

    private fun renderSelection(candidate: WorkshopSelection, persist: Boolean) {
        val normalized = WorkshopCatalog.normalize(candidate)
        selection = if (persist) settings.update(normalized) else normalized
        val resolved = WorkshopCatalog.resolve(selection)
        applyingSelection = true
        try {
            themeButtons.forEach { (id, button) -> button.isSelected = id == selection.themeId }
            petButtons.forEach { (id, button) -> button.isSelected = id == selection.petId }
            petEnabled.isSelected = selection.petEnabled
        } finally {
            applyingSelection = false
        }
        // A disabled pet is absent from the live resolved selection, but the workshop preview must
        // still render the user's selected catalog entry rather than falling back to Pixel Cat.
        val previewResolved = if (resolved.pet == null) {
            WorkshopCatalog.resolve(selection.copy(petEnabled = true))
        } else {
            resolved
        }
        previewPet.appearance = previewResolved.toDesktopPetAppearance()
        previewPet.isPetEnabled = true
        previewTitle.text = if (selection.petEnabled) {
            "${resolved.pet?.displayName ?: "桌宠"} 已在工作台待命"
        } else {
            "桌宠预览 · 当前未启用"
        }
        previewDescription.text = previewResolved.pet?.description.orEmpty()
        status.text = if (persist) "已应用 · ${resolved.theme.displayName}" else "当前 · ${resolved.theme.displayName}"
        applyColors(resolved)
        onSelectionChanged(resolved)
    }

    private fun applyColors(resolved: ResolvedWorkshopSelection) {
        val colors = resolved.toUiColors()
        background = colors.background
        scrollContent.background = colors.background
        scroll.viewport.background = colors.background
        themedSurfaces.forEach { panel ->
            panel.background = colors.surface
            val inner = (panel.getClientProperty(INNER_BORDER_KEY) as? javax.swing.border.Border)
                ?: (panel.border ?: JBUI.Borders.empty()).also { panel.putClientProperty(INNER_BORDER_KEY, it) }
            panel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(colors.border),
                inner,
            )
        }
        themeButtons.values.forEach { it.applyColors(colors) }
        petButtons.values.forEach { it.applyColors(colors) }
        petEnabled.foreground = colors.primaryText
        previewTitle.foreground = colors.primaryText
        previewDescription.foreground = colors.secondaryText
        status.foreground = colors.accent
        updateDescendantLabelColors(scrollContent, colors)
        revalidate()
        repaint()
    }

    private fun themedSurface(layout: BorderLayout): JPanel = WorkshopSurfacePanel(layout).also { panel ->
        panel.isOpaque = true
        panel.alignmentX = LEFT_ALIGNMENT
        themedSurfaces += panel
    }

    private companion object {
        const val INNER_BORDER_KEY = "omnicode.workshop.innerBorder"
    }
}

private class WorkshopSurfacePanel(layout: BorderLayout) : JPanel(layout) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class WorkshopChoiceGridPanel : JPanel(GridLayout(0, 2, JBUI.scale(8), JBUI.scale(8))) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class WorkshopChoiceButton(
    title: String,
    description: String,
    icon: Icon?,
) : JToggleButton("<html><b>$title</b><br><span>$description</span></html>", icon) {
    private var palette = WorkshopCatalog.themes.first().palette.toUiColors()

    init {
        horizontalAlignment = LEFT
        verticalAlignment = CENTER
        iconTextGap = JBUI.scale(10)
        isFocusPainted = true
        isOpaque = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(10, 12)
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(96))
    }

    fun applyColors(colors: WorkshopUiColors) {
        palette = colors
        foreground = colors.primaryText
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.color = if (isSelected) palette.elevatedSurface else palette.surface
            val arc = JBUI.scale(10)
            copy.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            copy.color = if (isSelected) palette.accent else palette.border
            copy.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (isSelected) {
                copy.fillRoundRect(0, JBUI.scale(12), JBUI.scale(3), height - JBUI.scale(24), 3, 3)
            }
        } finally {
            copy.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class ThemeSwatchIcon(
    private val background: Color,
    private val surface: Color,
    private val accent: Color,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(38)
    override fun getIconHeight(): Int = JBUI.scale(38)

    override fun paintIcon(component: java.awt.Component?, graphics: Graphics, x: Int, y: Int) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            copy.color = background
            copy.fillRoundRect(x, y, iconWidth, iconHeight, arc, arc)
            copy.color = surface
            copy.fillRoundRect(x + JBUI.scale(5), y + JBUI.scale(5), iconWidth - JBUI.scale(10), iconHeight - JBUI.scale(10), arc, arc)
            copy.color = accent
            copy.fillOval(x + iconWidth - JBUI.scale(13), y + JBUI.scale(5), JBUI.scale(8), JBUI.scale(8))
        } finally {
            copy.dispose()
        }
    }
}

private fun updateDescendantLabelColors(component: java.awt.Component, colors: WorkshopUiColors) {
    if (component is JBLabel && component.foreground != colors.accent) {
        component.foreground = if (component.font?.isBold == true) colors.primaryText else colors.secondaryText
    }
    if (component is java.awt.Container) component.components.forEach { child ->
        updateDescendantLabelColors(child, colors)
    }
}
