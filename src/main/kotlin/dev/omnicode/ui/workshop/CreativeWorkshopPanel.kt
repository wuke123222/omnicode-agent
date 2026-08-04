package dev.omnicode.ui.workshop

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.workshop.ResolvedWorkshopSelection
import dev.omnicode.workshop.CustomPetAvatarStore
import dev.omnicode.workshop.PetDisplayMode
import dev.omnicode.workshop.PetPlacementSettings
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
import javax.swing.JFileChooser
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Curated, local-only creative workshop. Catalog entries are compiled data; the optional user
 * image is loaded only through [CustomPetAvatarStore]. Scripts, classes, remote resources, and
 * executable pet behaviour are never loaded.
 */
internal class CreativeWorkshopPanel(
    private val onSelectionChanged: (ResolvedWorkshopSelection) -> Unit,
    private val settings: WorkshopSettingsService = WorkshopSettingsService.getInstance(),
    private val avatarStore: CustomPetAvatarStore = CustomPetAvatarStore.shared,
) : JPanel(BorderLayout()), Disposable {
    private val themeButtons = linkedMapOf<String, WorkshopChoiceButton>()
    private val petButtons = linkedMapOf<String, WorkshopChoiceButton>()
    private val themedSurfaces = mutableListOf<JPanel>()
    private val petEnabled = JCheckBox("在聊天工作台显示桌宠")
    private val embeddedPetMode = JToggleButton("工具窗口内")
    private val floatingPetMode = JToggleButton("浮动到桌面")
    private val resetPetPosition = JButton("复位位置")
    private val importAvatarButton = JButton("导入偶像立绘…")
    private val removeAvatarButton = JButton("移除自定义")
    private val previewPet = DesktopPetPanel(initiallyEnabled = true, placementSettingsOverride = null)
    private val previewStateButtons = linkedMapOf<DesktopPetState, JToggleButton>()
    private val previewTitle = JBLabel()
    private val previewDescription = JBLabel()
    private val status = JBLabel("选择会立即保存并应用到当前工作台").apply { font = JBFont.small() }
    private val scrollContent = JPanel()
    private val scroll = JBScrollPane(scrollContent)
    private var selection: WorkshopSelection = settings.snapshot()
    private var applyingSelection = false
    private var disposed = false
    private val placementListener: (PetPlacementSettings) -> Unit = {
        val refresh = { if (!disposed) renderPetPlacement() }
        if (SwingUtilities.isEventDispatchThread()) refresh() else SwingUtilities.invokeLater(refresh)
    }

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
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildPetPlacementControls())
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
        settings.addPlacementListener(placementListener)
        renderSelection(settings.snapshot(), persist = false)
    }

    override fun dispose() {
        disposed = true
        settings.removePlacementListener(placementListener)
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
            add(JBLabel("给编码空间换肤，并选择会回应 Agent 状态的桌宠或虚拟偶像。").apply {
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(JBLabel("安全设计：本地立绘会被解码并重新编码，不执行脚本、命令或远程资源。").apply {
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
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))
        petEnabled.addActionListener {
            if (!applyingSelection) renderSelection(selection.copy(petEnabled = petEnabled.isSelected), persist = true)
        }
        add(petEnabled, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(importAvatarButton.apply {
                toolTipText = "从桌面或项目中导入您有权使用的 PNG/JPG 角色立绘"
                accessibleContext.accessibleName = "导入虚拟偶像立绘"
                addActionListener { chooseCustomAvatar() }
            })
            add(removeAvatarButton.apply {
                toolTipText = "删除 OmniCode 本地保存的自定义立绘"
                accessibleContext.accessibleName = "移除自定义虚拟偶像立绘"
                addActionListener { removeCustomAvatar() }
            })
            add(JButton("恢复默认").apply {
                toolTipText = "恢复默认皮肤，并关闭桌宠"
                addActionListener { renderSelection(WorkshopCatalog.defaultSelection(), persist = true) }
            })
        }, BorderLayout.EAST)
    }

    private fun buildPetPlacementControls(): JComponent = themedSurface(BorderLayout(JBUI.scale(12), 0)).apply {
        border = JBUI.Borders.empty(10, 12)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("桌宠位置").apply {
                font = JBFont.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(JBLabel("可在工具窗口内拖动；桌面浮窗不抢输入焦点，并会自动留在可见屏幕内。").apply {
                font = JBFont.small()
                alignmentX = LEFT_ALIGNMENT
            })
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            val group = ButtonGroup()
            group.add(embeddedPetMode)
            group.add(floatingPetMode)
            add(embeddedPetMode.apply {
                toolTipText = "把桌宠停靠回 OmniCode 工具窗口"
                addActionListener {
                    settings.setPetDisplayMode(PetDisplayMode.EMBEDDED)
                    renderPetPlacement()
                    status.text = "桌宠已停靠到工具窗口 · 可直接拖动"
                }
            })
            add(floatingPetMode.apply {
                isEnabled = isDesktopPetFloatingSupported()
                toolTipText = if (isEnabled) {
                    "使用不抢焦点的透明桌面浮窗；右键可关闭或复位"
                } else {
                    "当前桌面环境不支持透明浮窗，已保持工具窗口模式"
                }
                addActionListener {
                    if (!selection.petEnabled) {
                        renderSelection(selection.copy(petEnabled = true), persist = true)
                    }
                    settings.setPetDisplayMode(PetDisplayMode.FLOATING)
                    renderPetPlacement()
                    status.text = "桌宠已浮动到桌面 · 拖动可记忆位置，右键可管理"
                }
            })
            add(resetPetPosition.apply {
                toolTipText = "清除工具窗口与桌面位置记录，回到推荐位置"
                addActionListener {
                    settings.resetPetPosition()
                    renderPetPlacement()
                    status.text = "桌宠位置已复位"
                }
            })
        }, BorderLayout.EAST)
    }

    private fun chooseCustomAvatar() {
        val confirmed = Messages.showYesNoDialog(
            null,
            "请只导入您拥有版权或已获授权使用的角色立绘。OmniCode 不会下载、上传或授权第三方角色素材。",
            "导入本地虚拟偶像",
            "继续选择",
            "取消",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        val chooser = JFileChooser().apply {
            dialogTitle = "选择虚拟偶像立绘"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("PNG / JPG 图片", "png", "jpg", "jpeg")
            approveButtonText = "导入"
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selectedPath = chooser.selectedFile.toPath()
        importAvatarButton.isEnabled = false
        status.text = "正在本地解码并净化立绘…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { avatarStore.importImage(selectedPath) }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                importAvatarButton.isEnabled = true
                result.onSuccess { info ->
                    renderSelection(
                        selection.copy(petId = WorkshopCatalog.CUSTOM_PET_ID, petEnabled = true),
                        persist = true,
                    )
                    status.text = "已导入 ${info.width} × ${info.height} 安全 PNG · 仅保存在本机"
                }.onFailure { error ->
                    Messages.showErrorDialog(
                        this,
                        error.message ?: "图片无法解码或超出安全限制",
                        "无法导入虚拟偶像",
                    )
                }
            }
        }
    }

    private fun removeCustomAvatar() {
        if (!avatarStore.exists()) return
        val confirmed = Messages.showYesNoDialog(
            null,
            "删除本机保存的自定义虚拟偶像立绘？此操作不会删除原始图片。",
            "移除自定义立绘",
            "移除",
            "取消",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        runCatching { avatarStore.remove() }
            .onSuccess {
                val next = if (selection.petId == WorkshopCatalog.CUSTOM_PET_ID) {
                    selection.copy(petId = WorkshopCatalog.DEFAULT_PET_ID, petEnabled = false)
                } else {
                    selection
                }
                renderSelection(next, persist = true)
                status.text = "已移除本机自定义立绘"
            }
            .onFailure { error ->
                Messages.showErrorDialog(this, error.message ?: "无法删除本地立绘", "移除失败")
            }
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
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildPreviewStateControls().apply { alignmentX = LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
    }

    private fun buildPreviewStateControls(): JComponent {
        val labels = mapOf(
            DesktopPetState.IDLE to "待命",
            DesktopPetState.THINKING to "思考",
            DesktopPetState.TOOL to "工具",
            DesktopPetState.SUCCESS to "完成",
            DesktopPetState.ERROR to "失败",
        )
        val group = ButtonGroup()
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            labels.forEach { (state, label) ->
                add(JToggleButton(label).apply {
                    isSelected = state == DesktopPetState.IDLE
                    toolTipText = "预览桌宠${state.accessibleLabel}状态"
                    accessibleContext.accessibleName = "预览${state.accessibleLabel}"
                    addActionListener { previewPet.state = state }
                    group.add(this)
                    previewStateButtons[state] = this
                })
            }
        }
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
        previewPet.appearance = previewResolved.toDesktopPetAppearance(avatarStore)
        previewPet.isPetEnabled = true
        previewTitle.text = if (selection.petEnabled) {
            "${resolved.pet?.displayName ?: "桌宠"} 已在工作台待命"
        } else {
            "桌宠预览 · 当前未启用"
        }
        previewDescription.text = previewResolved.pet?.description.orEmpty()
        status.text = if (persist) "已应用 · ${resolved.theme.displayName}" else "当前 · ${resolved.theme.displayName}"
        removeAvatarButton.isEnabled = avatarStore.exists()
        renderPetPlacement()
        applyColors(resolved)
        onSelectionChanged(resolved)
    }

    private fun renderPetPlacement() {
        val mode = settings.placementSnapshot().displayMode
        embeddedPetMode.isSelected = mode == PetDisplayMode.EMBEDDED
        floatingPetMode.isSelected = mode == PetDisplayMode.FLOATING
        resetPetPosition.isEnabled = settings.placementSnapshot().let { placement ->
            placement.embeddedX != null || placement.floatingX != null
        }
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
