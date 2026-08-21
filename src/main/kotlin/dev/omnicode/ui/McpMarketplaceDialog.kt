package dev.omnicode.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.mcp.McpCatalogEntry
import dev.omnicode.mcp.McpCatalogAvailability
import dev.omnicode.mcp.McpCatalogCategory
import dev.omnicode.mcp.McpCatalogInstallKind
import dev.omnicode.mcp.McpCatalogInstallOption
import dev.omnicode.mcp.McpCatalogQuery
import dev.omnicode.mcp.McpCatalogRiskLevel
import dev.omnicode.mcp.McpCatalogSource
import dev.omnicode.mcp.McpMarketplaceCatalog
import dev.omnicode.mcp.McpRegistryException
import dev.omnicode.mcp.McpRegistryFailureKind
import dev.omnicode.mcp.McpSecurityFindingSeverity
import dev.omnicode.mcp.scanMcpInstall
import dev.omnicode.settings.McpTransport
import java.io.IOException
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.DocumentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class McpMarketplaceLayoutMode { COMPACT, WIDE }

internal enum class McpRegistryUiState { LOCAL_ONLY, LOADING, READY, EMPTY, OFFLINE, FAILED }

internal data class McpRegistryStatusPresentation(
    val text: String,
    val isError: Boolean = false,
)

internal const val MCP_MARKETPLACE_WIDE_THRESHOLD = 720
internal const val MCP_MARKETPLACE_MAX_RESULTS = 600

internal fun mcpMarketplaceLayoutMode(
    width: Int,
    wideThreshold: Int = MCP_MARKETPLACE_WIDE_THRESHOLD,
): McpMarketplaceLayoutMode = if (width < wideThreshold) {
    McpMarketplaceLayoutMode.COMPACT
} else {
    McpMarketplaceLayoutMode.WIDE
}

internal fun mergeMcpMarketplaceEntries(
    localEntries: List<McpCatalogEntry>,
    registryEntries: List<McpCatalogEntry>,
): List<McpCatalogEntry> {
    val merged = LinkedHashMap<String, McpCatalogEntry>()
    for (entry in localEntries) {
        if (merged.size >= MAX_CATALOG_CANDIDATES) break
        merged.putIfAbsent(entry.id, entry)
    }
    for (entry in registryEntries) {
        if (merged.size >= MAX_CATALOG_CANDIDATES) break
        merged.putIfAbsent(entry.id, entry)
    }
    return merged.values.toList()
}

internal fun mcpMarketplaceSourceBadge(source: McpCatalogSource): String = when (source) {
    McpCatalogSource.BUILT_IN_PRESET -> "内置精选"
    McpCatalogSource.MCP_REGISTRY -> "Registry · 未审阅"
}

internal fun mcpMarketplaceSourceFilterLabel(source: McpCatalogSource): String = when (source) {
    McpCatalogSource.BUILT_IN_PRESET -> "内置精选"
    McpCatalogSource.MCP_REGISTRY -> "Registry（未审阅）"
}

internal fun mcpMarketplaceCanAdd(entry: McpCatalogEntry): Boolean = entry.installOptions.isNotEmpty()

internal fun mcpRegistryStatusPresentation(
    state: McpRegistryUiState,
    registryCount: Int = 0,
    totalCount: Int = registryCount,
    retainedRegistryCount: Int = 0,
): McpRegistryStatusPresentation = when (state) {
    McpRegistryUiState.LOCAL_ONLY -> McpRegistryStatusPresentation("本地精选已就绪 · Registry 尚未加载")
    McpRegistryUiState.LOADING -> McpRegistryStatusPresentation("正在加载 Registry… · 本地精选仍可浏览")
    McpRegistryUiState.READY -> McpRegistryStatusPresentation(
        "目录共 $totalCount 条 · Registry $registryCount 条未经 OmniCode 审阅",
    )
    McpRegistryUiState.EMPTY -> McpRegistryStatusPresentation("Registry 暂无可用条目 · 当前显示本地精选")
    McpRegistryUiState.OFFLINE -> McpRegistryStatusPresentation(
        if (retainedRegistryCount > 0) {
            "Registry 离线 · 保留上次加载的 $retainedRegistryCount 条与本地精选"
        } else {
            "Registry 离线 · 当前仍可浏览本地精选"
        },
    )
    McpRegistryUiState.FAILED -> McpRegistryStatusPresentation(
        if (retainedRegistryCount > 0) {
            "Registry 加载失败 · 保留上次加载的 $retainedRegistryCount 条与本地精选"
        } else {
            "Registry 加载失败 · 当前仍可浏览本地精选"
        },
        isError = true,
    )
}

internal fun mcpRegistryFailureState(error: Throwable): McpRegistryUiState =
    if (generateSequence(error as Throwable?) { it.cause }.take(MAX_ERROR_CAUSE_DEPTH).any {
            it is IOException || (it is McpRegistryException && it.kind in setOf(
                McpRegistryFailureKind.NETWORK,
                McpRegistryFailureKind.TIMEOUT,
            ))
        }
    ) {
        McpRegistryUiState.OFFLINE
    } else {
        McpRegistryUiState.FAILED
    }

/**
 * A bounded browser for the compiled fallback catalog and unreviewed Registry metadata.
 *
 * This dialog deliberately owns no launcher, connector, credential store, or settings service.
 * Selecting and filtering entries are in-memory operations. The two callbacks are the only way
 * out: callers may add a disabled draft or focus an already configured server.
 */
internal class McpMarketplaceDialog(
    project: Project?,
    private val isInstalled: (McpCatalogEntry) -> Boolean,
    private val onAdd: (entry: McpCatalogEntry, optionId: String) -> Unit,
    private val onViewInstalled: (McpCatalogEntry) -> Unit,
    private val registryLoader: suspend (forceRefresh: Boolean) -> List<McpCatalogEntry> = { emptyList() },
    private val registryCacheState: () -> Boolean = { false },
) : DialogWrapper(project) {
    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Replaced atomically when filtering. Adding hundreds of Registry rows one by one to the
     * live model emits one Swing event per row and can monopolize the EDT long enough to look
     * like the IDE has frozen.
     */
    private var resultsModel = DefaultListModel<McpCatalogEntry>()
    private val installedByEntryId = linkedMapOf<String, Boolean>()
    private var registryEntries: List<McpCatalogEntry> = emptyList()
    private var catalogEntries: List<McpCatalogEntry> = McpMarketplaceCatalog.entries
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "搜索服务器、发布者或标签"
        textEditor.accessibleContext?.accessibleName = "搜索 MCP 市场"
        (textEditor.document as? AbstractDocument)?.documentFilter = BoundedTextFilter(MAX_SEARCH_CHARS)
    }
    private val sourceFilter = JComboBox<SourceChoice>().apply {
        model = DefaultComboBoxModel(
            (listOf(SourceChoice(null, "全部来源")) + McpCatalogSource.entries.map {
                SourceChoice(it, mcpMarketplaceSourceFilterLabel(it))
            }).toTypedArray(),
        )
        accessibleContext?.accessibleName = "筛选 MCP 来源"
    }
    private val categoryFilter = JComboBox<CategoryChoice>().apply {
        model = DefaultComboBoxModel(
            (listOf(CategoryChoice(null, "全部分类")) + McpCatalogCategory.entries.map {
                CategoryChoice(it, it.displayName)
            }).toTypedArray(),
        )
        accessibleContext?.accessibleName = "筛选 MCP 分类"
    }
    private val availabilityFilter = JComboBox<AvailabilityChoice>().apply {
        model = DefaultComboBoxModel(AvailabilityChoice.entries)
        accessibleContext?.accessibleName = "筛选 MCP 可用性"
        toolTipText = "按是否存在兼容的安全配置草案筛选"
    }
    private val resetButton = JButton("重置").apply {
        toolTipText = "清除搜索并显示全部已加载条目"
        accessibleContext?.accessibleName = toolTipText
    }
    private val refreshRegistryButton = JButton("刷新 Registry").apply {
        toolTipText = "强制刷新远端 Registry；失败时本地精选仍可使用"
        accessibleContext?.accessibleName = toolTipText
    }
    private val registryStatus = JBLabel().apply {
        font = JBFont.small()
        foreground = OmniCodeUiPalette.secondary
    }
    private val resultCount = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val resultList = JBList(resultsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(104)
        cellRenderer = CatalogEntryRenderer()
        border = JBUI.Borders.empty(4)
        accessibleContext?.accessibleName = "MCP 市场结果"
        toolTipText = "选择服务器查看配置、权限和安装方式"
    }
    private val openDetailsButton = JButton("查看详情").apply {
        isEnabled = false
        isVisible = false
        accessibleContext?.accessibleName = "查看选中的 MCP 服务器详情"
    }
    private val backButton = JButton("\u2190 返回市场").apply {
        isVisible = false
        accessibleContext?.accessibleName = "返回 MCP 市场列表"
    }
    private val detailContent = ConversationColumn().apply {
        border = JBUI.Borders.empty(12)
    }
    private val detailScroll = JBScrollPane(detailContent).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(18)
        accessibleContext?.accessibleName = "MCP 服务器详情"
    }
    private val listSection = buildListSection()
    private val detailSection = buildDetailSection()
    private val wideSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
        resizeWeight = 0.46
        dividerSize = JBUI.scale(1)
        isContinuousLayout = true
        border = JBUI.Borders.customLine(OmniCodeUiPalette.border)
    }
    private val compactLayout = CardLayout()
    private val compactPages = JPanel(compactLayout).apply { isOpaque = false }
    private val compactListPage = JPanel(BorderLayout()).apply { isOpaque = false }
    private val compactDetailPage = JPanel(BorderLayout()).apply { isOpaque = false }
    private val adaptiveHost = JPanel(BorderLayout()).apply { isOpaque = false }
    private val filtersPanel = buildFilters()
    private val centerPanel = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(1_020), JBUI.scale(650))
        minimumSize = Dimension(JBUI.scale(480), JBUI.scale(420))
        border = JBUI.Borders.empty(10, 12, 8, 12)
        add(filtersPanel, BorderLayout.NORTH)
        add(adaptiveHost, BorderLayout.CENTER)
    }
    private val footerStatus = JBLabel("").apply {
        foreground = OmniCodeUiPalette.error
        font = JBFont.small()
    }

    private var selectedOptionId: String? = null
    private var appliedLayoutMode: McpMarketplaceLayoutMode? = null
    private var compactShowingDetails = false
    private var registryLoadGeneration = 0L
    @Volatile
    private var disposed = false

    init {
        title = "OmniCode \u00b7 MCP 市场"
        setOKButtonText("添加服务器")
        setCancelButtonText("取消")
        isOKActionEnabled = false

        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = applyFilter()
            override fun removeUpdate(event: DocumentEvent) = applyFilter()
            override fun changedUpdate(event: DocumentEvent) = applyFilter()
        })
        sourceFilter.addActionListener { applyFilter() }
        categoryFilter.addActionListener { applyFilter() }
        availabilityFilter.addActionListener { applyFilter() }
        resetButton.addActionListener { resetFilters() }
        refreshRegistryButton.addActionListener { loadRegistry(forceRefresh = true) }
        resultList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) updateDetails(resultList.selectedValue)
        }
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2) showCompactDetails()
            }
        })
        resultList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), OPEN_DETAILS_ACTION)
        resultList.actionMap.put(OPEN_DETAILS_ACTION, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = showCompactDetails()
        })
        openDetailsButton.addActionListener { showCompactDetails() }
        backButton.addActionListener { showCompactList() }

        centerPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                installResponsiveLayout(
                    mcpMarketplaceLayoutMode(centerPanel.width, JBUI.scale(MCP_MARKETPLACE_WIDE_THRESHOLD)),
                )
            }
        })
        installResponsiveLayout(McpMarketplaceLayoutMode.WIDE)
        updateRegistryStatus(McpRegistryUiState.LOCAL_ONLY)
        applyFilter()
        init()
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            installResponsiveLayout(
                mcpMarketplaceLayoutMode(centerPanel.width, JBUI.scale(MCP_MARKETPLACE_WIDE_THRESHOLD)),
            )
            loadRegistry(forceRefresh = false)
        }
    }

    override fun dispose() {
        disposed = true
        registryLoadGeneration++
        registryScope.cancel()
        super.dispose()
    }

    override fun createNorthPanel(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = OmniCodeUiPalette.surface
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(OmniCodeUiPalette.border, 0, 0, 1, 0),
            JBUI.Borders.empty(16, 20),
        )
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("MCP 市场").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 5f)
            })
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(JBLabel("添加后立即启用、保存并自动测试连接（首次连接需审批）；Registry 条目未经审阅，请先核对命令与权限。").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = OmniCodeUiPalette.secondary
            })
        }, BorderLayout.CENTER)
    }

    override fun createCenterPanel(): JComponent = centerPanel

    override fun createSouthPanel(): JComponent {
        val actions = super.createSouthPanel()
        return JPanel(BorderLayout(JBUI.scale(14), 0)).apply {
            isOpaque = true
            background = OmniCodeUiPalette.surface
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(OmniCodeUiPalette.border, 1, 0, 0, 0),
                JBUI.Borders.empty(10, 16),
            )
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JBLabel("\u26e8  添加前请核对命令、URL、参数、凭据占位符与权限边界。").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = OmniCodeUiPalette.primary
                    font = JBFont.small()
                })
                add(Box.createVerticalStrut(JBUI.scale(3)))
                add(footerStatus.apply { alignmentX = Component.LEFT_ALIGNMENT })
            }, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun getDimensionServiceKey(): String = "OmniCode.McpMarketplace"

    override fun doOKAction() {
        val entry = resultList.selectedValue ?: return
        footerStatus.text = ""
        val result = if (installedByEntryId[entry.id] == true) {
            runCatching { onViewInstalled(entry) }
        } else {
            val optionId = selectedOptionId ?: return
            runCatching { onAdd(entry, optionId) }
        }
        result.onSuccess { super.doOKAction() }
            .onFailure { error ->
                footerStatus.text = "操作失败：${boundedText(error.message ?: error::class.java.simpleName, 160)}"
            }
    }

    private fun buildFilters(): JPanel = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(8)
    }

    private fun layoutFilters(compact: Boolean) {
        filtersPanel.removeAll()
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        if (compact) {
            categoryFilter.preferredSize = null
            sourceFilter.preferredSize = null
            availabilityFilter.preferredSize = null
            constraints.gridx = 0
            constraints.gridy = 0
            constraints.gridwidth = 4
            constraints.weightx = 1.0
            constraints.insets = Insets(0, 0, JBUI.scale(7), 0)
            filtersPanel.add(search, constraints)
            constraints.gridy = 1
            constraints.gridwidth = 1
            constraints.weightx = 1.0
            constraints.insets = Insets(0, 0, JBUI.scale(7), JBUI.scale(8))
            filtersPanel.add(categoryFilter, constraints)
            constraints.gridx = 1
            constraints.insets = Insets(0, 0, JBUI.scale(7), JBUI.scale(8))
            filtersPanel.add(sourceFilter, constraints)
            constraints.gridx = 2
            constraints.insets = Insets(0, 0, JBUI.scale(7), 0)
            filtersPanel.add(availabilityFilter, constraints)
            constraints.gridx = 0
            constraints.gridy = 2
            constraints.gridwidth = 4
            constraints.weightx = 1.0
            constraints.insets = Insets(0, 0, JBUI.scale(5), 0)
            filtersPanel.add(registryStatus, constraints)
            constraints.gridy = 3
            constraints.insets = Insets(0, 0, 0, 0)
            filtersPanel.add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(refreshRegistryButton)
                add(resetButton)
            }, constraints)
        } else {
            constraints.gridx = 0
            constraints.gridy = 0
            constraints.weightx = 1.0
            constraints.insets = Insets(0, 0, 0, JBUI.scale(8))
            filtersPanel.add(search, constraints)
            constraints.gridx++
            constraints.weightx = 0.0
            categoryFilter.preferredSize = Dimension(JBUI.scale(170), categoryFilter.preferredSize.height)
            filtersPanel.add(categoryFilter, constraints)
            constraints.gridx++
            sourceFilter.preferredSize = Dimension(JBUI.scale(180), sourceFilter.preferredSize.height)
            filtersPanel.add(sourceFilter, constraints)
            constraints.gridx++
            constraints.weightx = 0.0
            constraints.insets = Insets(0, 0, 0, JBUI.scale(8))
            availabilityFilter.preferredSize = Dimension(JBUI.scale(150), availabilityFilter.preferredSize.height)
            filtersPanel.add(availabilityFilter, constraints)
            constraints.gridx++
            constraints.insets = Insets(0, 0, 0, JBUI.scale(8))
            filtersPanel.add(refreshRegistryButton, constraints)
            constraints.gridx++
            constraints.insets = Insets(0, 0, 0, 0)
            filtersPanel.add(resetButton, constraints)
            constraints.gridx = 0
            constraints.gridy = 1
            constraints.gridwidth = 6
            constraints.weightx = 1.0
            constraints.insets = Insets(JBUI.scale(6), 0, 0, 0)
            filtersPanel.add(registryStatus, constraints)
        }
        filtersPanel.revalidate()
        filtersPanel.repaint()
    }

    private fun buildListSection(): JComponent = JPanel(BorderLayout(0, JBUI.scale(7))).apply {
        isOpaque = false
        minimumSize = Dimension(JBUI.scale(260), JBUI.scale(240))
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2)
            add(JBLabel("服务器目录").apply {
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold()
            }, BorderLayout.WEST)
            add(resultCount, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(RoundedSurfacePanel(
            fillColor = OmniCodeUiPalette.surface,
            outlineColor = OmniCodeUiPalette.border,
            radius = 10,
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(3)
            add(JBScrollPane(resultList).apply {
                border = JBUI.Borders.empty()
                viewport.isOpaque = false
                isOpaque = false
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = JBUI.scale(18)
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(openDetailsButton)
        }, BorderLayout.SOUTH)
    }

    private fun buildDetailSection(): JComponent = JPanel(BorderLayout(0, JBUI.scale(7))).apply {
        isOpaque = false
        minimumSize = Dimension(JBUI.scale(280), JBUI.scale(240))
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(backButton, BorderLayout.WEST)
            add(JBLabel("服务器详情").apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(RoundedSurfacePanel(
            fillColor = OmniCodeUiPalette.surface,
            outlineColor = OmniCodeUiPalette.border,
            radius = 10,
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(3)
            add(detailScroll, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }

    private fun applyFilter() {
        val previousId = resultList.selectedValue?.id
        val selectedSource = (sourceFilter.selectedItem as? SourceChoice)?.source
        val selectedCategory = (categoryFilter.selectedItem as? CategoryChoice)?.category
        val selectedAvailability = (availabilityFilter.selectedItem as? AvailabilityChoice)?.availability
            ?: McpCatalogAvailability.ALL
        val query = McpCatalogQuery(
            text = search.text.take(MAX_SEARCH_CHARS),
            sources = selectedSource?.let(::setOf).orEmpty(),
            categories = selectedCategory?.let(::setOf).orEmpty(),
            availability = selectedAvailability,
            maxResults = MCP_MARKETPLACE_MAX_RESULTS,
        )
        val matches = McpMarketplaceCatalog.search(catalogEntries, query)
        matches.forEach { entry ->
            installedByEntryId.computeIfAbsent(entry.id) {
                runCatching { isInstalled(entry) }.getOrDefault(false)
            }
        }

        val nextModel = DefaultListModel<McpCatalogEntry>().also { model ->
            matches.forEach(model::addElement)
        }
        resultsModel = nextModel
        resultList.model = nextModel
        val installableCount = catalogEntries.count { it.installOptions.isNotEmpty() }
        resultCount.text = "${matches.size} / ${catalogEntries.size} · 可添加 $installableCount"
        val nextIndex = matches.indexOfFirst { it.id == previousId }.takeIf { it >= 0 }
            ?: 0.takeIf { matches.isNotEmpty() }
            ?: -1
        resultList.selectedIndex = nextIndex
        if (nextIndex >= 0) resultList.ensureIndexIsVisible(nextIndex)
        if (nextIndex < 0) updateDetails(null)
        resultList.repaint()
        if (matches.isEmpty()) showCompactList()
    }

    private fun loadRegistry(forceRefresh: Boolean) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { if (!disposed) loadRegistry(forceRefresh) }
            return
        }
        if (disposed || !refreshRegistryButton.isEnabled && registryLoadGeneration > 0L) return
        val generation = ++registryLoadGeneration
        refreshRegistryButton.isEnabled = false
        updateRegistryStatus(McpRegistryUiState.LOADING)
        registryScope.launch {
            val result = runCatching { registryLoader(forceRefresh).take(MAX_CATALOG_CANDIDATES) }
            SwingUtilities.invokeLater {
                if (disposed || generation != registryLoadGeneration) return@invokeLater
                refreshRegistryButton.isEnabled = true
                result.onSuccess { loaded ->
                    registryEntries = loaded.toList()
                    catalogEntries = mergeMcpMarketplaceEntries(McpMarketplaceCatalog.entries, registryEntries)
                    updateRegistryStatus(
                        if (registryEntries.isEmpty()) McpRegistryUiState.EMPTY else McpRegistryUiState.READY,
                        fromCache = registryCacheState(),
                    )
                    applyFilter()
                }.onFailure { error ->
                    updateRegistryStatus(mcpRegistryFailureState(error))
                }
            }
        }
    }

    private fun updateRegistryStatus(state: McpRegistryUiState, fromCache: Boolean = false) {
        val visibleRegistryCount = catalogEntries.count { it.source != McpCatalogSource.BUILT_IN_PRESET }
        val presentation = mcpRegistryStatusPresentation(
            state = state,
            registryCount = visibleRegistryCount,
            totalCount = catalogEntries.size,
            retainedRegistryCount = visibleRegistryCount,
        )
        registryStatus.text = if (fromCache && state == McpRegistryUiState.READY) {
            "${presentation.text} · 本机缓存，可点击刷新获取最新目录"
        } else {
            presentation.text
        }
        registryStatus.foreground = if (presentation.isError) OmniCodeUiPalette.error else OmniCodeUiPalette.secondary
        registryStatus.toolTipText = presentation.text
        registryStatus.accessibleContext?.accessibleName = presentation.text
    }

    private fun resetFilters() {
        search.text = ""
        sourceFilter.selectedIndex = 0
        categoryFilter.selectedIndex = 0
        availabilityFilter.selectedIndex = 0
        applyFilter()
        search.textEditor.requestFocusInWindow()
    }

    private fun updateDetails(entry: McpCatalogEntry?) {
        detailContent.removeAll()
        footerStatus.text = ""
        if (entry == null) {
            selectedOptionId = null
            openDetailsButton.isEnabled = false
            isOKActionEnabled = false
            detailContent.add(emptyDetails())
            detailContent.revalidate()
            detailContent.repaint()
            return
        }

        val installed = installedByEntryId[entry.id] ?: runCatching { isInstalled(entry) }.getOrDefault(false)
        val installable = mcpMarketplaceCanAdd(entry)
        openDetailsButton.isEnabled = true
        selectedOptionId = entry.installOptions.firstOrNull()?.id
        setOKButtonText(when {
            installed -> "查看已添加"
            installable -> "添加服务器"
            else -> "仅浏览"
        })
        isOKActionEnabled = installed || selectedOptionId != null

        detailContent.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel(entry.name).apply {
                    foreground = OmniCodeUiPalette.primary
                    font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 3f)
                })
                add(badge(mcpMarketplaceSourceBadge(entry.source), sourceBadgeColor(entry.source)))
                add(badge(
                    when {
                        installed -> "已添加"
                        installable -> "可添加"
                        else -> "仅浏览"
                    },
                    when {
                        installed -> OmniCodeUiPalette.secondary
                        installable -> OmniCodeUiPalette.success
                        else -> OmniCodeUiPalette.warning
                    },
                ))
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel(entry.publisher).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
        })
        detailContent.add(Box.createVerticalStrut(JBUI.scale(10)))
        detailContent.add(wrappedText(entry.description, OmniCodeUiPalette.primary).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        detailContent.add(Box.createVerticalStrut(JBUI.scale(10)))
        detailContent.add(tagRow(entry).apply { alignmentX = Component.LEFT_ALIGNMENT })

        if (entry.links.isNotEmpty()) {
            detailContent.add(Box.createVerticalStrut(JBUI.scale(9)))
            detailContent.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                entry.links.forEach { link ->
                    add(JButton(link.kind.displayName).apply {
                        toolTipText = boundedText(link.url, 240)
                        accessibleContext?.accessibleName = "打开 ${entry.name} ${link.kind.displayName}"
                        addActionListener { BrowserUtil.browse(link.url) }
                    })
                }
            })
        }

        detailContent.add(Box.createVerticalStrut(JBUI.scale(14)))
        detailContent.add(JBLabel("安装方式").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = OmniCodeUiPalette.primary
            font = JBFont.label().asBold()
        })
        detailContent.add(Box.createVerticalStrut(JBUI.scale(6)))
        if (entry.installOptions.isEmpty()) {
            detailContent.add(RoundedSurfacePanel(
                fillColor = OmniCodeUiPalette.timelineElevated,
                outlineColor = OmniCodeUiPalette.border,
                radius = 9,
            ).apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(9, 11)
                add(JBLabel("仅浏览 · 暂无兼容安装方式").apply {
                    foreground = OmniCodeUiPalette.secondary
                    font = JBFont.label().asBold()
                }, BorderLayout.CENTER)
                accessibleContext?.accessibleName = "${entry.name} 仅可浏览，暂无兼容安装方式"
            })
            val declarations = entry.registryMetadata?.installDeclarations.orEmpty()
            if (declarations.isNotEmpty()) {
                detailContent.add(Box.createVerticalStrut(JBUI.scale(7)))
                val reasons = declarations.mapNotNull { declaration ->
                    declaration.unavailableReason.takeIf(String::isNotBlank)
                }.distinct().take(3)
                detailContent.add(wrappedText(
                    if (reasons.isEmpty()) {
                        "Registry 已提供 ${declarations.size} 个安装声明，但当前版本没有可安全转换的一键配置。"
                    } else {
                        "Registry 安装声明：${reasons.joinToString("；")}"
                    },
                    OmniCodeUiPalette.secondary,
                ).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        } else {
            val optionDetails = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val optionCombo = JComboBox(entry.installOptions.toTypedArray()).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                renderer = InstallOptionRenderer()
                accessibleContext?.accessibleName = "选择 ${entry.name} 的安装方式"
                addActionListener {
                    val option = selectedItem as? McpCatalogInstallOption ?: return@addActionListener
                    selectedOptionId = option.id
                    renderOptionDetails(entry, option, optionDetails)
                    isOKActionEnabled = installed || selectedOptionId != null
                }
            }
            detailContent.add(optionCombo)
            detailContent.add(Box.createVerticalStrut(JBUI.scale(8)))
            detailContent.add(optionDetails)
            renderOptionDetails(entry, entry.installOptions.first(), optionDetails)
        }

        detailContent.add(Box.createVerticalStrut(JBUI.scale(12)))
        detailContent.add(riskCard(entry).apply { alignmentX = Component.LEFT_ALIGNMENT })
        detailContent.add(Box.createVerticalGlue())
        detailContent.revalidate()
        detailContent.repaint()
        detailScroll.viewport.viewPosition = java.awt.Point(0, 0)
    }

    private fun renderOptionDetails(
        entry: McpCatalogEntry,
        option: McpCatalogInstallOption,
        host: JPanel,
    ) {
        host.removeAll()
        val endpoint = when (option.transport) {
            McpTransport.STDIO -> buildString {
                append(option.command)
                if (option.arguments.isNotEmpty()) append(' ').append(option.arguments.joinToString(" "))
            }
            McpTransport.HTTP -> option.url
        }
        host.add(metadataLine("传输", option.transport.displayName))
        host.add(Box.createVerticalStrut(JBUI.scale(4)))
        host.add(metadataLine(if (option.transport == McpTransport.STDIO) "命令预览" else "Endpoint", boundedText(endpoint, 300)))
        if (option.environmentKeys.isNotEmpty()) {
            host.add(Box.createVerticalStrut(JBUI.scale(4)))
            host.add(metadataLine("凭据占位符", option.environmentKeys.joinToString(", ")))
        }
        val security = scanMcpInstall(entry, option)
        if (security.findings.isNotEmpty()) {
            host.add(Box.createVerticalStrut(JBUI.scale(7)))
            host.add(RoundedSurfacePanel(
                fillColor = OmniCodeUiPalette.controlWarning,
                outlineColor = OmniCodeUiPalette.warning,
                radius = 8,
            ).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 10)
                add(JBLabel("安装前安全检查").apply { font = JBFont.small().asBold() })
                security.findings.take(4).forEach { finding ->
                    add(JBLabel("• ${finding.message}").apply {
                        foreground = if (finding.severity == McpSecurityFindingSeverity.BLOCKING) {
                            OmniCodeUiPalette.error
                        } else {
                            OmniCodeUiPalette.secondary
                        }
                        font = JBFont.small()
                    })
                }
            })
        }
        optionDownloadWarning(option)?.let { warning ->
            host.add(Box.createVerticalStrut(JBUI.scale(7)))
            host.add(wrappedText(warning, OmniCodeUiPalette.warning))
        }
        host.accessibleContext?.accessibleName = "${entry.name} ${option.displayName} 配置预览"
        host.revalidate()
        host.repaint()
    }

    private fun riskCard(entry: McpCatalogEntry): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.controlWarning,
        outlineColor = riskColor(entry.riskLevel),
        radius = 9,
    ).apply {
        layout = BorderLayout(0, JBUI.scale(5))
        border = JBUI.Borders.empty(10, 12)
        add(JBLabel("\u26a0  ${entry.riskLevel.displayName} · 启用前必须核对").apply {
            foreground = riskColor(entry.riskLevel)
            font = JBFont.label().asBold()
        }, BorderLayout.NORTH)
        add(wrappedText(entry.riskSummary, OmniCodeUiPalette.primary), BorderLayout.CENTER)
        accessibleContext?.accessibleName = "${entry.riskLevel.displayName}：${entry.riskSummary}"
    }

    private fun emptyDetails(): JComponent = JPanel(GridBagLayout()).apply {
        isOpaque = false
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("没有匹配的 MCP 服务器").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold()
            })
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(JBLabel("修改搜索词或重置来源筛选。").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = OmniCodeUiPalette.secondary
            })
        }
        add(content)
    }

    private fun installResponsiveLayout(mode: McpMarketplaceLayoutMode) {
        if (appliedLayoutMode == mode) return
        appliedLayoutMode = mode
        layoutFilters(mode == McpMarketplaceLayoutMode.COMPACT)
        detach(listSection)
        detach(detailSection)
        adaptiveHost.removeAll()
        when (mode) {
            McpMarketplaceLayoutMode.WIDE -> {
                backButton.isVisible = false
                openDetailsButton.isVisible = false
                wideSplit.leftComponent = listSection
                wideSplit.rightComponent = detailSection
                adaptiveHost.add(wideSplit, BorderLayout.CENTER)
                SwingUtilities.invokeLater {
                    if (wideSplit.isDisplayable) wideSplit.setDividerLocation(0.46)
                }
            }
            McpMarketplaceLayoutMode.COMPACT -> {
                backButton.isVisible = true
                openDetailsButton.isVisible = true
                compactListPage.removeAll()
                compactDetailPage.removeAll()
                compactListPage.add(listSection, BorderLayout.CENTER)
                compactDetailPage.add(detailSection, BorderLayout.CENTER)
                compactPages.removeAll()
                compactPages.add(compactListPage, COMPACT_LIST_CARD)
                compactPages.add(compactDetailPage, COMPACT_DETAIL_CARD)
                adaptiveHost.add(compactPages, BorderLayout.CENTER)
                if (compactShowingDetails && resultList.selectedValue != null) {
                    compactLayout.show(compactPages, COMPACT_DETAIL_CARD)
                } else {
                    compactLayout.show(compactPages, COMPACT_LIST_CARD)
                }
            }
        }
        adaptiveHost.revalidate()
        adaptiveHost.repaint()
    }

    private fun showCompactDetails() {
        if (appliedLayoutMode != McpMarketplaceLayoutMode.COMPACT || resultList.selectedValue == null) return
        compactShowingDetails = true
        compactLayout.show(compactPages, COMPACT_DETAIL_CARD)
        detailScroll.requestFocusInWindow()
    }

    private fun showCompactList() {
        compactShowingDetails = false
        if (appliedLayoutMode == McpMarketplaceLayoutMode.COMPACT) {
            compactLayout.show(compactPages, COMPACT_LIST_CARD)
            resultList.requestFocusInWindow()
        }
    }

    private fun detach(component: JComponent) {
        component.parent?.remove(component)
    }

    private fun tagRow(entry: McpCatalogEntry): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        entry.tags.take(MAX_VISIBLE_TAGS).forEach { tag -> add(badge(tag, OmniCodeUiPalette.accent)) }
    }

    private fun metadataLine(label: String, value: String): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel(label).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small().asBold()
        }, BorderLayout.WEST)
        add(JBLabel(value).apply {
            foreground = OmniCodeUiPalette.primary
            font = JBFont.small()
            toolTipText = value.takeIf { it.length > 80 }
        }, BorderLayout.CENTER)
    }

    private fun badge(text: String, color: java.awt.Color): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.controlSelected,
        outlineColor = color,
        radius = 12,
    ).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(2, 7)
        add(JBLabel(text).apply {
            foreground = color
            font = JBFont.small()
        }, BorderLayout.CENTER)
    }

    private inner class CatalogEntryRenderer : ListCellRenderer<McpCatalogEntry> {
        override fun getListCellRendererComponent(
            list: JList<out McpCatalogEntry>,
            value: McpCatalogEntry,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val installed = installedByEntryId[value.id] == true
            return RoundedSurfacePanel(
                fillColor = if (isSelected) OmniCodeUiPalette.controlSelected else OmniCodeUiPalette.surface,
                outlineColor = if (isSelected) OmniCodeUiPalette.accent else OmniCodeUiPalette.border,
                radius = 10,
            ).apply {
                layout = BorderLayout(JBUI.scale(10), 0)
                border = EmptyBorder(JBUI.scale(10), JBUI.scale(11), JBUI.scale(10), JBUI.scale(11))
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(RoundedSurfacePanel(
                        fillColor = OmniCodeUiPalette.accent,
                        outlineColor = null,
                        radius = 9,
                    ).apply {
                        layout = GridBagLayout()
                        preferredSize = Dimension(JBUI.scale(42), JBUI.scale(42))
                        minimumSize = preferredSize
                        add(JBLabel("@").apply {
                            foreground = OmniCodeUiPalette.surface
                            font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 4f)
                        })
                    }, BorderLayout.NORTH)
                }, BorderLayout.WEST)
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        isOpaque = false
                        alignmentX = Component.LEFT_ALIGNMENT
                        add(JBLabel(boundedText(value.name, 64)).apply {
                            foreground = OmniCodeUiPalette.primary
                            font = JBFont.label().asBold()
                        })
                        add(badge(mcpMarketplaceSourceBadge(value.source), sourceBadgeColor(value.source)))
                        add(badge(
                            when {
                                installed -> "已添加"
                                mcpMarketplaceCanAdd(value) -> "可添加"
                                else -> "仅浏览"
                            },
                            when {
                                installed -> OmniCodeUiPalette.secondary
                                mcpMarketplaceCanAdd(value) -> OmniCodeUiPalette.success
                                else -> OmniCodeUiPalette.warning
                            },
                        ))
                    })
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(JBLabel(boundedText(value.description, 100)).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        foreground = OmniCodeUiPalette.primary
                        toolTipText = value.description
                    })
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(JBLabel("${mcpMarketplaceSourceFilterLabel(value.source)}  ·  ${value.publisher}").apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        foreground = OmniCodeUiPalette.secondary
                        font = JBFont.small()
                    })
                }, BorderLayout.CENTER)
                accessibleContext?.accessibleName = buildString {
                    append(value.name).append("，").append(value.publisher).append("，")
                    append(when {
                        installed -> "已添加"
                        mcpMarketplaceCanAdd(value) -> "可添加"
                        else -> "仅浏览，暂无兼容安装方式"
                    })
                }
            }
        }
    }

    private class InstallOptionRenderer : JLabel(), ListCellRenderer<McpCatalogInstallOption> {
        override fun getListCellRendererComponent(
            list: JList<out McpCatalogInstallOption>,
            value: McpCatalogInstallOption,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component = this.apply {
            text = value.displayName
            isOpaque = true
            background = if (isSelected) list.selectionBackground else list.background
            foreground = if (isSelected) list.selectionForeground else list.foreground
            border = JBUI.Borders.empty(3, 7)
        }
    }

    private data class SourceChoice(
        val source: McpCatalogSource?,
        val label: String,
    ) {
        override fun toString(): String = label
    }

    private data class CategoryChoice(
        val category: McpCatalogCategory?,
        val label: String,
    ) {
        override fun toString(): String = label
    }

    private data class AvailabilityChoice(
        val availability: McpCatalogAvailability,
        val label: String,
    ) {
        override fun toString(): String = label

        companion object {
            val entries: Array<AvailabilityChoice> = arrayOf(
                AvailabilityChoice(McpCatalogAvailability.ALL, "全部状态"),
                AvailabilityChoice(McpCatalogAvailability.INSTALLABLE, "可添加"),
                AvailabilityChoice(McpCatalogAvailability.BROWSE_ONLY, "仅浏览"),
            )
        }
    }

    private companion object {
        const val MAX_SEARCH_CHARS = 160
        const val MAX_VISIBLE_TAGS = 8
        const val OPEN_DETAILS_ACTION = "omnicode.mcp.marketplace.openDetails"
        const val COMPACT_LIST_CARD = "list"
        const val COMPACT_DETAIL_CARD = "detail"
    }
}

private const val MAX_CATALOG_CANDIDATES = 2_000
private const val MAX_ERROR_CAUSE_DEPTH = 16

private fun sourceBadgeColor(source: McpCatalogSource): java.awt.Color = when (source) {
    McpCatalogSource.BUILT_IN_PRESET -> OmniCodeUiPalette.accent
    McpCatalogSource.MCP_REGISTRY -> OmniCodeUiPalette.warning
}

internal fun optionDownloadWarning(option: McpCatalogInstallOption): String? = when (option.kind) {
    McpCatalogInstallKind.NPX_PACKAGE,
    McpCatalogInstallKind.UVX_PACKAGE,
    -> "此方式首次启动时可能联网下载并执行第三方包；添加后会立即启用并测试连接，启动前会弹出审批确认。"
    McpCatalogInstallKind.LOCAL_EXECUTABLE ->
        "此方式要求本机已有对应可执行文件；市场不会探测、下载或启动它。"
    McpCatalogInstallKind.STREAMABLE_HTTP ->
        "此方式会在启用并获批后连接远端 Endpoint；市场浏览阶段不会发起请求。"
}

private fun riskColor(level: McpCatalogRiskLevel): java.awt.Color = when (level) {
    McpCatalogRiskLevel.LOW -> OmniCodeUiPalette.success
    McpCatalogRiskLevel.MEDIUM -> OmniCodeUiPalette.warning
    McpCatalogRiskLevel.HIGH -> OmniCodeUiPalette.error
}

private fun wrappedText(value: String, foreground: java.awt.Color): JTextArea = JTextArea(value).apply {
    isEditable = false
    isFocusable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    border = null
    this.foreground = foreground
    font = JBFont.label()
    rows = ((value.length + WRAPPED_TEXT_CHARS_PER_ROW - 1) / WRAPPED_TEXT_CHARS_PER_ROW)
        .coerceIn(1, MAX_WRAPPED_TEXT_ROWS)
    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private fun boundedText(value: String, maxChars: Int): String {
    val normalized = value.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take((maxChars - 1).coerceAtLeast(0)) + "\u2026"
}

private class BoundedTextFilter(
    private val maxChars: Int,
) : DocumentFilter() {
    @Throws(BadLocationException::class)
    override fun insertString(filterBypass: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
        replace(filterBypass, offset, 0, string, attr)
    }

    @Throws(BadLocationException::class)
    override fun replace(
        filterBypass: FilterBypass,
        offset: Int,
        length: Int,
        text: String?,
        attrs: AttributeSet?,
    ) {
        val safe = text.orEmpty().filterNot(Char::isISOControl)
        val available = (maxChars - (filterBypass.document.length - length)).coerceAtLeast(0)
        filterBypass.replace(offset, length, safe.take(available), attrs)
    }
}

private const val WRAPPED_TEXT_CHARS_PER_ROW = 42
private const val MAX_WRAPPED_TEXT_ROWS = 6
