package dev.omnicode.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.commercial.EntitlementSource
import dev.omnicode.commercial.MarketplaceLicenseStatus
import dev.omnicode.commercial.OmniCodeEntitlementService
import dev.omnicode.commercial.OmniCodePaidFeature
import dev.omnicode.commercial.OmniCodePlan
import java.awt.BorderLayout
import java.awt.FlowLayout
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
    private val buyProButton = JButton("试用 / 购买 Pro")
    private val refreshLicenseButton = JButton("刷新 JetBrains 权益")
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
        buyProButton.addActionListener {
            entitlementService.requestMarketplaceLicense("启动 OmniCode Pro 试用或管理已购许可证。")
            marketplaceStatusLabel.text = "已打开 JetBrains 许可证窗口；完成后点击“刷新 JetBrains 权益”。"
        }
        refreshLicenseButton.addActionListener {
            entitlementService.refreshMarketplace()
            refreshSummary()
        }
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
        add(JBLabel("OmniCode Pro").apply { font = JBFont.h2().asBold() })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(planLabel.apply { font = JBFont.label().asBold() })
        add(JBLabel("核心 Agent、Plan、MCP 和基础历史保持可用；付费权益只解锁可选的高级产物。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(5)
        })
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(JBLabel("JetBrains Marketplace 许可证").apply { font = JBFont.label().asBold() })
        add(JBLabel("试用、购买、续费、退款、发票和许可证由 JetBrains Account 统一管理，插件不接触支付资料。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(3)
        })
        add(JBLabel("购买后 IDE 会保存 JetBrains 签发的确认信息；OmniCode 只验证固定产品 POMNICODEAGENT 的签名。").apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(3)
        })
        add(Box.createVerticalStrut(JBUI.scale(7)))
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(buyProButton)
            add(refreshLicenseButton)
        })
        add(marketplaceStatusLabel.apply {
            foreground = dev.omnicode.ui.OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(5)
        })
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(JBLabel("旧版签名许可证迁移").apply { font = JBFont.label().asBold() })
        add(JBLabel("仅供已有用户；新用户无需粘贴 token。旧许可证仍只保存在 IDE Password Safe。").apply {
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
                    statusLabel.text = "保存后将清除旧版兼容许可证；JetBrains Account 许可证不受影响。"
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
        add(JBLabel("Pro 是 JetBrains Marketplace Freemium 权益；基础 Agent、Plan、MCP、科研和可靠性功能继续免费。").apply {
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
            entitlement.source == EntitlementSource.JETBRAINS_MARKETPLACE -> "JetBrains Marketplace Pro 许可证已验证。"
            entitlement.source == EntitlementSource.LOCAL_PREVIEW -> "本地开发预览已解锁全部付费界面；不会进入发布包。"
            entitlement.plan == OmniCodePlan.FREE -> "当前为 Free；可在 JetBrains 中启动试用或购买 Pro。"
            else -> "旧版签名许可证已验证并保存在 IDE Password Safe。"
        }
        marketplaceStatusLabel.text = if (entitlement.source == EntitlementSource.LOCAL_PREVIEW) {
            "本地开发预览不模拟付款；正式包仍由 JetBrains Account 决定 Pro 权益。"
        } else {
            when (entitlementService.marketplaceStatus()) {
                MarketplaceLicenseStatus.INITIALIZING -> "JetBrains 许可证服务正在初始化；稍后可手动刷新。"
                MarketplaceLicenseStatus.LICENSED -> "已连接 JetBrains Account，Pro 权益有效。"
                MarketplaceLicenseStatus.UNLICENSED -> "尚未发现 POMNICODEAGENT 许可证；可启动试用或购买。"
            }
        }
    }
}
