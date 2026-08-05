package dev.omnicode.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.commercial.OmniCodeEntitlementService
import dev.omnicode.commercial.OmniCodePaidFeature
import dev.omnicode.commercial.OmniCodePlan
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/** Embedded sidebar page for activation and a transparent explanation of paid capabilities. */
internal class OmniCodeCommercialEmbeddedSettings : OmniCodeEmbeddedSettings {
    private val entitlementService = OmniCodeEntitlementService.getInstance()
    private val tokenField = JPasswordField(42)
    private val planLabel = JBLabel()
    private val statusLabel = JBLabel()
    private var clearRequested = false

    override val component: JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(12))).apply {
        isOpaque = false
        add(summaryPanel(), BorderLayout.NORTH)
        add(featurePanel(), BorderLayout.CENTER)
    }

    override val isModified: Boolean
        get() = clearRequested || tokenField.password.isNotEmpty()

    init {
        tokenField.toolTipText = "粘贴 OmniCode 服务端签发的签名许可证；插件不会把它写入项目文件。"
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
        add(JBLabel("OmniCode Pro / Research").apply { font = JBFont.h2().asBold() })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(planLabel.apply { font = JBFont.label().asBold() })
        add(JBLabel("核心 Agent、Plan、MCP 和基础历史保持可用；付费权益只解锁可选的高级产物。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(5)
        })
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(JBLabel("激活签名许可证").apply { font = JBFont.label().asBold() })
        add(JBLabel("许可证由服务端签发并使用 Ed25519 校验。不要把 token 提交到 Git 或项目 Harness。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(3)
        })
        add(Box.createVerticalStrut(JBUI.scale(5)))
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(tokenField, BorderLayout.CENTER)
            add(JButton("清除").apply {
                addActionListener {
                    clearRequested = true
                    tokenField.text = ""
                    statusLabel.text = "保存后将清除本机许可证。"
                }
            }, BorderLayout.EAST)
        })
        add(statusLabel.apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(5)
        })
    }

    private fun featurePanel(): JComponent = card().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel("可购买的高级能力").apply { font = JBFont.label().asBold() })
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
        add(JBLabel("购买、团队席位和发票由 OmniCode 商业服务处理；本页只负责本机安全激活，不伪造付款状态。 ").apply {
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
        planLabel.text = "当前计划：${entitlement.displayLabel()}"
        statusLabel.text = when {
            entitlement.plan == OmniCodePlan.FREE -> "尚未激活付费许可证。"
            else -> "许可证已验证并保存在 IDE Password Safe。"
        }
    }
}
