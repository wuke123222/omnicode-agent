package dev.omnicode.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.commercial.EntitlementSource
import dev.omnicode.commercial.OmniCodeEntitlementService
import dev.omnicode.commercial.OmniCodePaidFeature
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/** Embedded sidebar page explaining that all OmniCode capabilities are included for free. */
internal class OmniCodeCommercialEmbeddedSettings : OmniCodeEmbeddedSettings {
    private val entitlementService = OmniCodeEntitlementService.getInstance()
    private val tokenField = JPasswordField(42)
    private val buyProButton = JButton("无需购买")
    private val refreshLicenseButton = JButton("无需激活")
    private val planLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val marketplaceStatusLabel = JBLabel()
    private var clearRequested = false

    override val component: JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(12))).apply {
        isOpaque = false
        add(summaryPanel(), BorderLayout.NORTH)
        add(featurePanel(), BorderLayout.CENTER)
    }

    override val isModified: Boolean
        get() = clearRequested || tokenField.password.isNotEmpty()

    init {
        tokenField.toolTipText = "仅供已经收到旧版 OmniCode 签名许可证的用户迁移；新购买由 JetBrains Marketplace 管理。"
        buyProButton.isEnabled = false
        refreshLicenseButton.isEnabled = false
        reset()
    }

    override fun save() {
        val token = tokenField.password
        try {
            when {
                clearRequested -> entitlementService.clear()
                token.isNotEmpty() -> entitlementService.activate(String(token))
            }
        } catch (error: RuntimeException) {
            throw OmniCodeSettingsSaveException(error.message ?: "许可证校验失败。", error)
        } finally {
            token.fill('\u0000')
            tokenField.text = ""
            clearRequested = false
        }
        refreshSummary()
    }

    override fun reset() {
        tokenField.text = ""
        clearRequested = false
        refreshSummary()
    }

    override fun dispose() {
        tokenField.text = ""
    }

    private fun summaryPanel(): JComponent = card().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel("OmniCode 免费能力").apply { font = JBFont.h2().asBold() })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(planLabel.apply { font = JBFont.label().asBold() })
        add(JBLabel("Agent、Plan、Team、MCP、科研、报告和导出能力全部开放，无需购买或许可证。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(5)
        })
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(JBLabel("当前版本不包含付费计划、试用、购买或许可证校验；所有功能均可直接使用。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(3)
        })
    }

    private fun featurePanel(): JComponent = card().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel("已开放能力").apply { font = JBFont.label().asBold() })
        add(Box.createVerticalStrut(JBUI.scale(7)))
        OmniCodePaidFeature.entries.forEach { feature ->
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBLabel(feature.displayName).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
                add(JBLabel("${feature.minimumPlan.displayName} · ${feature.description}").apply {
                    foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
                }, BorderLayout.CENTER)
            })
            add(Box.createVerticalStrut(JBUI.scale(7)))
        }
        add(JBLabel("所有能力均为免费功能，不需要配置付款或许可证。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(4)
        })
    }

    private fun card(): JPanel = JPanel().apply {
        isOpaque = true
        background = dev.omnicode.ui.OmniCodeUiPalette.surface
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(dev.omnicode.ui.OmniCodeUiPalette.border),
            JBUI.Borders.empty(12),
        )
    }

    private fun refreshSummary() {
        val entitlement = entitlementService.current()
        planLabel.text = "当前计划：Free（全部功能已开放）"
        statusLabel.text = when {
            entitlement.source == EntitlementSource.LOCAL_PREVIEW -> "本地开发预览已开启；正式版本同样无需许可证。"
            else -> "所有功能已开放，无需激活。"
        }
        marketplaceStatusLabel.text = "无需连接 Marketplace 许可证服务。"
    }
}
