package dev.omnicode.ui.workshop

import com.intellij.openapi.Disposable
import dev.omnicode.workshop.PetDisplayMode
import dev.omnicode.workshop.PetPlacementSettings
import dev.omnicode.workshop.WorkshopSettingsService
import dev.omnicode.workshop.WorkshopPetVisual
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JLayeredPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Visual states understood by [DesktopPetPanel]. */
enum class DesktopPetState(
    val accessibleLabel: String,
    internal val animated: Boolean,
) {
    IDLE("待命", true),
    THINKING("思考中", true),
    TOOL("正在使用工具", true),
    SUCCESS("任务完成", false),
    ERROR("任务出错", false),
}

/** Theme colors are supplied as data so the pet does not depend on a particular IDE theme. */
data class DesktopPetTheme(
    val body: Color,
    val face: Color,
    val outline: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    val shadow: Color,
) {
    companion object {
        fun defaults(): DesktopPetTheme {
            val panel = UIManager.getColor("Panel.background") ?: Color(0xF4F5F7)
            val foreground = UIManager.getColor("Label.foreground") ?: Color(0x2B2D31)
            val dark = panel.red + panel.green + panel.blue < 3 * 128
            return if (dark) {
                DesktopPetTheme(
                    body = Color(0x3D4658),
                    face = Color(0x252A33),
                    outline = Color(0x6B7892),
                    foreground = Color(0xE6E9EF),
                    muted = Color(0x9DA6B8),
                    accent = Color(0x7AA2F7),
                    success = Color(0x73C991),
                    error = Color(0xF0757D),
                    shadow = Color(0, 0, 0, 72),
                )
            } else {
                DesktopPetTheme(
                    body = Color(0xDDE6F8),
                    face = Color(0xF8FAFF),
                    outline = Color(0x7384A5),
                    foreground = foreground,
                    muted = Color(0x667085),
                    accent = Color(0x4263D8),
                    success = Color(0x2F7D4A),
                    error = Color(0xB62E3A),
                    shadow = Color(0, 0, 0, 36),
                )
            }
        }
    }
}

/**
 * Logical geometry for the pet. Values are scaled uniformly to the component bounds.
 * Set [earHeight] to zero for a rounded robot instead of the default cat-like silhouette.
 */
data class DesktopPetShape(
    val preferredWidth: Int = 116,
    val preferredHeight: Int = 112,
    val bodyWidth: Int = 70,
    val bodyHeight: Int = 58,
    val cornerRadius: Int = 20,
    val earHeight: Int = 14,
    val eyeSize: Int = 7,
    val badgeSize: Int = 21,
) {
    init {
        require(preferredWidth >= 72) { "preferredWidth must be at least 72" }
        require(preferredHeight >= 72) { "preferredHeight must be at least 72" }
        require(bodyWidth in 32..preferredWidth) { "bodyWidth must fit inside preferredWidth" }
        require(bodyHeight in 28..preferredHeight) { "bodyHeight must fit inside preferredHeight" }
        require(cornerRadius in 0..min(bodyWidth, bodyHeight)) { "cornerRadius must fit inside the body" }
        require(earHeight in 0..bodyHeight / 2) { "earHeight must be between 0 and half the body height" }
        require(eyeSize in 2..bodyHeight / 3) { "eyeSize must fit inside the face" }
        require(badgeSize in 12..min(preferredWidth, preferredHeight) / 2) { "badgeSize is outside the supported range" }
    }
}

data class DesktopPetAppearance(
    val theme: DesktopPetTheme = DesktopPetTheme.defaults(),
    val shape: DesktopPetShape = DesktopPetShape(),
    val visual: WorkshopPetVisual = WorkshopPetVisual.CREATURE,
    val customAvatar: BufferedImage? = null,
)

/**
 * A self-contained, lightweight Swing desktop pet.
 *
 * The animation timer only runs while the component is showing, enabled, and in an animated
 * state. Removing the component from its hierarchy or calling [dispose] stops the timer.
 */
class DesktopPetPanel(
    initialState: DesktopPetState = DesktopPetState.IDLE,
    initialAppearance: DesktopPetAppearance = DesktopPetAppearance(),
    initiallyEnabled: Boolean = true,
    placementSettingsOverride: WorkshopSettingsService? = defaultPetPlacementSettings(),
) : JPanel(), Disposable {
    private var disposed = false
    private var phase = 0
    private val placementSettings = placementSettingsOverride
    private var placement: PetPlacementSettings = placementSettings?.placementSnapshot() ?: PetPlacementSettings()
    private var dockParent: Container? = null
    private var dockLayer: Int? = null
    private var floatingWindow: JWindow? = null
    private var internalBoundsChange = false
    private var dragScreenOrigin: Point? = null
    private var dragComponentOrigin: Point? = null
    private var dragMoved = false
    private val animationTimer = Timer(FRAME_DELAY_MS) {
        phase = (phase + 1) % FRAME_COUNT
        repaint()
    }.apply {
        isRepeats = true
        isCoalesce = true
    }
    private val hierarchyListener = HierarchyListener { event ->
        val lifecycleFlags = HierarchyEvent.SHOWING_CHANGED.toLong() or
            HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()
        if (event.changeFlags and lifecycleFlags != 0L) {
            syncAnimationTimer()
        }
    }
    private val placementListener: (PetPlacementSettings) -> Unit = { updated ->
        val apply = {
            if (!disposed) {
                placement = updated
                syncPlacementMode()
                revalidate()
                repaint()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) apply() else SwingUtilities.invokeLater(apply)
    }
    private val dragListener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (showPopupIfRequested(event)) return
            if (!SwingUtilities.isLeftMouseButton(event) || placementSettings == null) return
            dragScreenOrigin = runCatching { event.locationOnScreen }.getOrNull()
            dragComponentOrigin = floatingWindow?.location ?: location
            dragMoved = false
        }

        override fun mouseDragged(event: MouseEvent) {
            val screenOrigin = dragScreenOrigin ?: return
            val componentOrigin = dragComponentOrigin ?: return
            val current = runCatching { event.locationOnScreen }.getOrNull() ?: return
            val requested = Point(
                componentOrigin.x + current.x - screenOrigin.x,
                componentOrigin.y + current.y - screenOrigin.y,
            )
            dragMoved = true
            floatingWindow?.let { window ->
                val fallback = fallbackScreenBounds(window.owner)
                val clamped = clampFloatingPetLocation(
                    requested = requested,
                    petSize = window.size,
                    usableScreens = usableDesktopScreens(),
                    fallbackScreen = fallback,
                )
                window.location = clamped
            } ?: parent?.let { host ->
                val clamped = clampEmbeddedPetLocation(requested, host.size, size)
                setBoundsInternally(clamped.x, clamped.y, width, height)
            }
        }

        override fun mouseReleased(event: MouseEvent) {
            if (showPopupIfRequested(event)) {
                clearDrag()
                return
            }
            if (dragMoved) persistCurrentPosition()
            clearDrag()
        }

        override fun mouseClicked(event: MouseEvent) {
            val settings = placementSettings ?: return
            if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                settings.resetPetPosition()
            }
        }
    }

    var state: DesktopPetState = initialState
        set(value) {
            if (field == value) return
            field = value
            phase = 0
            updateAccessibleDescription()
            syncAnimationTimer()
            repaint()
        }

    var appearance: DesktopPetAppearance = initialAppearance
        set(value) {
            if (field == value) return
            field = value
            resizeFloatingWindow()
            revalidate()
            repaint()
        }

    var isPetEnabled: Boolean = initiallyEnabled
        set(value) {
            if (field == value) return
            field = value
            phase = 0
            updateAccessibleDescription()
            syncAnimationTimer()
            syncPlacementMode()
            repaint()
        }

    internal val isAnimationRunning: Boolean get() = animationTimer.isRunning
    internal val isDisposed: Boolean get() = disposed
    internal val isFloating: Boolean get() = floatingWindow != null

    init {
        isOpaque = false
        isFocusable = false
        addHierarchyListener(hierarchyListener)
        addMouseListener(dragListener)
        addMouseMotionListener(dragListener)
        placementSettings?.addPlacementListener(placementListener)
        super.getAccessibleContext()?.accessibleName = "OmniCode 桌宠"
        updateAccessibleDescription()
    }

    override fun getPreferredSize(): Dimension = appearance.shape.let {
        Dimension(it.preferredWidth, it.preferredHeight)
    }

    override fun getMinimumSize(): Dimension = Dimension(MINIMUM_SIZE, MINIMUM_SIZE)

    /** Only the visible mascot area captures input, leaving most of the overlay transparent. */
    override fun contains(x: Int, y: Int): Boolean = placementSettings != null && isPetEnabled &&
        x in INTERACTIVE_INSET until (width - INTERACTIVE_INSET).coerceAtLeast(INTERACTIVE_INSET) &&
        y in 0 until (height - STATUS_LABEL_HEIGHT).coerceAtLeast(1)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        if (internalBoundsChange) {
            super.setBounds(x, y, width, height)
            return
        }
        if (floatingWindow != null) return
        val host = parent
        val embeddedX = placement.embeddedX
        val embeddedY = placement.embeddedY
        if (placementSettings != null && host != null && embeddedX != null && embeddedY != null) {
            val target = embeddedPetLocation(host.size, Dimension(width, height), embeddedX, embeddedY)
            super.setBounds(target.x, target.y, width, height)
        } else {
            super.setBounds(x, y, width, height)
        }
    }

    override fun addNotify() {
        super.addNotify()
        captureDockParent()
        syncAnimationTimer()
        if (placement.displayMode == PetDisplayMode.FLOATING) {
            SwingUtilities.invokeLater { if (!disposed) syncPlacementMode() }
        }
    }

    override fun removeNotify() {
        animationTimer.stop()
        super.removeNotify()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (!isPetEnabled || width <= 0 || height <= 0) return

        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            copy.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            copy.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
            copy.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            paintPet(copy)
        } finally {
            copy.dispose()
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        animationTimer.stop()
        animationTimer.actionListeners.forEach(animationTimer::removeActionListener)
        removeHierarchyListener(hierarchyListener)
        removeMouseListener(dragListener)
        removeMouseMotionListener(dragListener)
        placementSettings?.removePlacementListener(placementListener)
        floatingWindow?.let { window ->
            window.contentPane.remove(this)
            window.dispose()
        }
        floatingWindow = null
    }

    private fun syncPlacementMode() {
        if (disposed || placementSettings == null) return
        when (placement.displayMode) {
            PetDisplayMode.EMBEDDED -> dockIntoToolWindow()
            PetDisplayMode.FLOATING -> floatOnDesktop()
        }
    }

    private fun captureDockParent() {
        val currentParent = parent ?: return
        if (floatingWindow?.contentPane === currentParent) return
        dockParent = currentParent
        dockLayer = (currentParent as? JLayeredPane)?.getLayer(this)
    }

    private fun floatOnDesktop() {
        val settings = placementSettings ?: return
        if (!isDesktopPetFloatingSupported()) {
            settings.setPetDisplayMode(PetDisplayMode.EMBEDDED)
            return
        }
        if (floatingWindow != null) {
            floatingWindow?.let { window ->
                positionFloatingWindow(window, settings)
                window.isVisible = isPetEnabled
            }
            return
        }
        if (!isPetEnabled) return
        captureDockParent()
        val originalParent = dockParent ?: return
        val owner = SwingUtilities.getWindowAncestor(originalParent)
        originalParent.remove(this)
        originalParent.revalidate()
        originalParent.repaint()

        val window = runCatching {
            JWindow(owner).apply {
                name = "OmniCodeDesktopPet"
                setFocusableWindowState(false)
                setAutoRequestFocus(false)
                setType(Window.Type.UTILITY)
                runCatching { background = Color(0, 0, 0, 0) }
                contentPane.layout = null
                (contentPane as? JPanel)?.isOpaque = false
            }
        }.getOrElse {
            restoreDockParent(originalParent)
            return
        }
        floatingWindow = window
        window.contentPane.add(this)
        resizeFloatingWindow()
        positionFloatingWindow(window, settings)
        window.isVisible = isPetEnabled
        updateAccessibleDescription()
    }

    private fun dockIntoToolWindow() {
        val window = floatingWindow ?: return
        window.isVisible = false
        window.contentPane.remove(this)
        window.dispose()
        floatingWindow = null
        dockParent?.let(::restoreDockParent)
        updateAccessibleDescription()
    }

    private fun restoreDockParent(host: Container) {
        if (parent === host) return
        when (host) {
            is JLayeredPane -> host.add(this, Integer.valueOf(dockLayer ?: JLayeredPane.PALETTE_LAYER))
            else -> host.add(this)
        }
        host.revalidate()
        host.repaint()
    }

    private fun resizeFloatingWindow() {
        val window = floatingWindow ?: return
        val target = preferredSize
        window.size = target
        setBoundsInternally(0, 0, target.width, target.height)
        val clamped = clampFloatingPetLocation(
            requested = window.location,
            petSize = target,
            usableScreens = usableDesktopScreens(),
            fallbackScreen = fallbackScreenBounds(window.owner),
        )
        window.location = clamped
    }

    private fun positionFloatingWindow(window: JWindow, settings: WorkshopSettingsService) {
        val requested = placement.floatingX?.let { x -> placement.floatingY?.let { y -> Point(x, y) } }
        val clamped = clampFloatingPetLocation(
            requested = requested,
            petSize = window.size,
            usableScreens = usableDesktopScreens(),
            fallbackScreen = fallbackScreenBounds(window.owner),
        )
        window.location = clamped
        if (requested != null && requested != clamped) settings.saveFloatingPetPosition(clamped.x, clamped.y)
    }

    private fun persistCurrentPosition() {
        val settings = placementSettings ?: return
        floatingWindow?.let { window ->
            settings.saveFloatingPetPosition(window.x, window.y)
            return
        }
        val host = parent ?: return
        val normalized = normalizedEmbeddedPetLocation(location, host.size, size)
        settings.saveEmbeddedPetPosition(normalized.x, normalized.y)
    }

    private fun showPopupIfRequested(event: MouseEvent): Boolean {
        val settings = placementSettings ?: return false
        if (!event.isPopupTrigger) return false
        JPopupMenu().apply {
            add(JMenuItem(if (floatingWindow == null) "浮动到桌面" else "停靠到工具窗口").apply {
                addActionListener {
                    settings.setPetDisplayMode(
                        if (floatingWindow == null) PetDisplayMode.FLOATING else PetDisplayMode.EMBEDDED,
                    )
                }
            })
            add(JMenuItem("复位位置").apply {
                addActionListener { settings.resetPetPosition() }
            })
            addSeparator()
            add(JMenuItem("关闭桌宠").apply {
                addActionListener {
                    isPetEnabled = false
                    settings.setPetEnabled(false)
                }
            })
            show(this@DesktopPetPanel, event.x, event.y)
        }
        return true
    }

    private fun clearDrag() {
        dragScreenOrigin = null
        dragComponentOrigin = null
        dragMoved = false
    }

    private fun setBoundsInternally(x: Int, y: Int, width: Int, height: Int) {
        internalBoundsChange = true
        try {
            super.setBounds(x, y, width, height)
        } finally {
            internalBoundsChange = false
        }
    }

    private fun fallbackScreenBounds(owner: Window?): Rectangle = owner?.graphicsConfiguration?.bounds
        ?: usableDesktopScreens().firstOrNull()
        ?: Rectangle(0, 0, preferredSize.width.coerceAtLeast(1), preferredSize.height.coerceAtLeast(1))

    private fun syncAnimationTimer() {
        val shouldRun = !disposed && isPetEnabled && state.animated && isShowing
        if (shouldRun && !animationTimer.isRunning) {
            animationTimer.start()
        } else if (!shouldRun && animationTimer.isRunning) {
            animationTimer.stop()
        }
    }

    private fun updateAccessibleDescription() {
        val description = if (isPetEnabled) {
            val placementDescription = if (floatingWindow == null) "工具窗口内，可拖动" else "桌面浮动，可拖动"
            "桌宠状态：${state.accessibleLabel}；$placementDescription，右键可管理"
        } else {
            "桌宠已关闭"
        }
        super.getAccessibleContext()?.accessibleDescription = description
        toolTipText = description
    }

    private fun paintPet(graphics: Graphics2D) {
        val shape = appearance.shape
        val availableWidth = (width - insets.left - insets.right).coerceAtLeast(1)
        val availableHeight = (height - insets.top - insets.bottom).coerceAtLeast(1)
        val scale = min(
            availableWidth.toDouble() / shape.preferredWidth,
            availableHeight.toDouble() / shape.preferredHeight,
        ).coerceAtLeast(0.01)
        val offsetX = insets.left + (availableWidth - shape.preferredWidth * scale) / 2.0
        val offsetY = insets.top + (availableHeight - shape.preferredHeight * scale) / 2.0
        graphics.translate(offsetX, offsetY)
        graphics.scale(scale, scale)

        val animation = animationFraction()
        val bob = when (state) {
            DesktopPetState.IDLE -> sin(animation * 2.0 * PI) * 1.4
            DesktopPetState.THINKING -> sin(animation * 4.0 * PI) * 1.0
            DesktopPetState.TOOL -> sin(animation * 6.0 * PI) * 0.8
            DesktopPetState.SUCCESS, DesktopPetState.ERROR -> 0.0
        }
        when (appearance.visual) {
            WorkshopPetVisual.IDOL_VOCALIST,
            WorkshopPetVisual.IDOL_GUITARIST,
            -> paintIdol(graphics, shape, animation, bob, appearance.visual)
            WorkshopPetVisual.CUSTOM_AVATAR -> paintCustomAvatar(graphics, shape, animation, bob)
            else -> {
                val bodyX = (shape.preferredWidth - shape.bodyWidth) / 2.0
                val bodyY = 20.0 + shape.earHeight * 0.45 + bob
                paintShadow(graphics, bodyX, bodyY, shape)
                if (appearance.visual == WorkshopPetVisual.CREATURE) {
                    paintTail(graphics, bodyX, bodyY, shape, animation)
                }
                if (appearance.visual == WorkshopPetVisual.CREATURE || appearance.visual == WorkshopPetVisual.OWL) {
                    paintEars(graphics, bodyX, bodyY, shape)
                }
                paintBody(graphics, bodyX, bodyY, shape)
                paintCompanionSilhouette(graphics, bodyX, bodyY, shape)
                paintFace(graphics, bodyX, bodyY, shape, animation)
                paintCompanionFeatures(graphics, bodyX, bodyY, shape, animation)
                paintBadge(graphics, bodyX, bodyY, shape, animation)
            }
        }
        paintStatus(graphics, shape)
    }

    private fun paintIdol(
        graphics: Graphics2D,
        shape: DesktopPetShape,
        animation: Double,
        bob: Double,
        visual: WorkshopPetVisual,
    ) {
        val centerX = shape.preferredWidth / 2.0
        val headY = 12.0 + bob
        val guitarist = visual == WorkshopPetVisual.IDOL_GUITARIST

        graphics.color = appearance.theme.shadow
        graphics.fill(Ellipse2D.Double(centerX - 25.0, 88.0, 50.0, 6.0))

        // Both performers are original OmniCode mascots. Their silhouette is intentionally generic
        // and assembled from simple vector shapes rather than tracing any third-party character.
        if (guitarist) {
            graphics.color = appearance.theme.body.darker()
            graphics.fill(RoundRectangle2D.Double(centerX - 25.0, headY + 3.0, 50.0, 52.0, 24.0, 24.0))
        } else {
            val longHair = Path2D.Double().apply {
                moveTo(centerX - 22.0, headY + 15.0)
                curveTo(centerX - 27.0, headY + 38.0, centerX - 19.0, headY + 67.0, centerX - 12.0, headY + 70.0)
                lineTo(centerX + 12.0, headY + 70.0)
                curveTo(centerX + 19.0, headY + 67.0, centerX + 27.0, headY + 38.0, centerX + 22.0, headY + 15.0)
                closePath()
            }
            graphics.color = appearance.theme.body.darker()
            graphics.fill(longHair)
        }

        val torso = Path2D.Double().apply {
            moveTo(centerX - 14.0, headY + 42.0)
            curveTo(centerX - 20.0, headY + 50.0, centerX - 19.0, headY + 68.0, centerX - 15.0, headY + 73.0)
            lineTo(centerX + 15.0, headY + 73.0)
            curveTo(centerX + 19.0, headY + 68.0, centerX + 20.0, headY + 50.0, centerX + 14.0, headY + 42.0)
            closePath()
        }
        graphics.color = appearance.theme.accent
        graphics.fill(torso)
        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(torso)

        graphics.color = appearance.theme.face
        graphics.fill(RoundRectangle2D.Double(centerX - 4.0, headY + 37.0, 8.0, 9.0, 4.0, 4.0))

        graphics.color = appearance.theme.body
        graphics.fill(Ellipse2D.Double(centerX - 22.0, headY, 44.0, 44.0))
        graphics.color = appearance.theme.outline
        graphics.draw(Ellipse2D.Double(centerX - 22.0, headY, 44.0, 44.0))
        graphics.color = appearance.theme.face
        graphics.fill(Ellipse2D.Double(centerX - 16.5, headY + 8.0, 33.0, 30.0))

        val bangs = Path2D.Double().apply {
            moveTo(centerX - 18.0, headY + 12.0)
            curveTo(centerX - 9.0, headY + 2.0, centerX + 10.0, headY + 2.0, centerX + 18.0, headY + 13.0)
            lineTo(centerX + 10.0, headY + 10.0)
            lineTo(centerX + 4.0, headY + 15.0)
            lineTo(centerX - 2.0, headY + 9.0)
            lineTo(centerX - 9.0, headY + 15.0)
            closePath()
        }
        graphics.color = appearance.theme.body
        graphics.fill(bangs)
        paintIdolFace(graphics, centerX, headY, animation)

        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Line2D.Double(centerX - 8.0, headY + 72.0, centerX - 9.0, 87.0))
        graphics.draw(Line2D.Double(centerX + 8.0, headY + 72.0, centerX + 9.0, 87.0))
        graphics.draw(Line2D.Double(centerX - 14.0, headY + 48.0, centerX - 23.0, headY + 60.0))
        graphics.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Line2D.Double(centerX - 13.0, 88.0, centerX - 6.0, 88.0))
        graphics.draw(Line2D.Double(centerX + 6.0, 88.0, centerX + 13.0, 88.0))

        if (guitarist) {
            paintGuitar(graphics, centerX, headY, animation)
            graphics.color = appearance.theme.accent
            graphics.fill(Ellipse2D.Double(centerX + 16.0, headY + 4.0, 8.0, 8.0))
        } else {
            paintMicrophone(graphics, centerX, headY, animation)
            paintStageStar(graphics, centerX - 26.0, headY + 3.0)
        }
        paintBadge(graphics, centerX - 35.0, headY + 1.0, shape, animation)
    }

    private fun paintIdolFace(graphics: Graphics2D, centerX: Double, headY: Double, animation: Double) {
        val eyeY = headY + 25.0
        val leftX = centerX - 7.0
        val rightX = centerX + 7.0
        graphics.color = stateColor()
        graphics.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (state) {
            DesktopPetState.SUCCESS -> {
                graphics.draw(Arc2D.Double(leftX - 3.0, eyeY - 1.0, 6.0, 5.0, 10.0, 160.0, Arc2D.OPEN))
                graphics.draw(Arc2D.Double(rightX - 3.0, eyeY - 1.0, 6.0, 5.0, 10.0, 160.0, Arc2D.OPEN))
            }
            DesktopPetState.ERROR -> {
                paintCross(graphics, leftX, eyeY, 5.0)
                paintCross(graphics, rightX, eyeY, 5.0)
            }
            else -> {
                val glance = if (state == DesktopPetState.THINKING) -1.0 else sin(animation * 2.0 * PI) * 0.6
                graphics.fill(Ellipse2D.Double(leftX - 2.0 + glance, eyeY - 2.0, 4.0, 5.0))
                graphics.fill(Ellipse2D.Double(rightX - 2.0 + glance, eyeY - 2.0, 4.0, 5.0))
            }
        }
        if (state == DesktopPetState.ERROR) {
            graphics.draw(Arc2D.Double(centerX - 4.0, headY + 31.0, 8.0, 5.0, 20.0, 140.0, Arc2D.OPEN))
        } else {
            graphics.draw(Arc2D.Double(centerX - 4.0, headY + 29.0, 8.0, 6.0, 200.0, 140.0, Arc2D.OPEN))
        }
    }

    private fun paintMicrophone(graphics: Graphics2D, centerX: Double, headY: Double, animation: Double) {
        val handX = centerX + 23.0
        val handY = headY + 54.0 + sin(animation * 4.0 * PI) * 1.2
        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Line2D.Double(centerX + 15.0, headY + 47.0, handX, handY))
        graphics.draw(Line2D.Double(handX, handY, handX + 2.0, handY + 13.0))
        graphics.color = appearance.theme.accent
        graphics.fill(Ellipse2D.Double(handX - 3.0, handY - 5.0, 7.0, 8.0))
    }

    private fun paintGuitar(graphics: Graphics2D, centerX: Double, headY: Double, animation: Double) {
        val guitarX = centerX + 13.0
        val guitarY = headY + 55.0
        graphics.color = appearance.theme.face
        graphics.fill(Ellipse2D.Double(guitarX - 9.0, guitarY - 8.0, 19.0, 17.0))
        graphics.color = appearance.theme.accent
        graphics.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Ellipse2D.Double(guitarX - 9.0, guitarY - 8.0, 19.0, 17.0))
        graphics.draw(Line2D.Double(guitarX + 7.0, guitarY - 5.0, guitarX + 18.0, guitarY - 17.0))
        val strum = sin(animation * 6.0 * PI) * 2.0
        graphics.draw(Line2D.Double(guitarX - 3.0, guitarY - 5.0 + strum, guitarX + 5.0, guitarY + 4.0 - strum))
    }

    private fun paintStageStar(graphics: Graphics2D, x: Double, y: Double) {
        graphics.color = appearance.theme.accent
        graphics.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Line2D.Double(x - 4.0, y, x + 4.0, y))
        graphics.draw(Line2D.Double(x, y - 4.0, x, y + 4.0))
    }

    private fun paintCustomAvatar(
        graphics: Graphics2D,
        shape: DesktopPetShape,
        animation: Double,
        bob: Double,
    ) {
        val frameSize = 76.0
        val x = (shape.preferredWidth - frameSize) / 2.0
        val y = 8.0 + bob
        graphics.color = appearance.theme.shadow
        graphics.fill(Ellipse2D.Double(x + 8.0, y + frameSize - 1.0, frameSize - 16.0, 8.0))
        graphics.color = appearance.theme.face
        graphics.fill(RoundRectangle2D.Double(x, y, frameSize, frameSize, 22.0, 22.0))
        graphics.color = appearance.theme.accent
        graphics.stroke = BasicStroke(2.0f)
        graphics.draw(RoundRectangle2D.Double(x, y, frameSize, frameSize, 22.0, 22.0))

        val image = appearance.customAvatar
        if (image == null) {
            graphics.color = appearance.theme.muted
            graphics.font = graphics.font.deriveFont(Font.BOLD, 28f)
            val marker = "+"
            val metrics = graphics.fontMetrics
            graphics.drawString(marker, (shape.preferredWidth - metrics.stringWidth(marker)) / 2, (y + 48.0).roundToInt())
        } else {
            val inset = 4.0
            val box = frameSize - inset * 2.0
            val scale = min(box / image.width, box / image.height)
            val drawWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
            val drawX = (x + (frameSize - drawWidth) / 2.0).roundToInt()
            val drawY = (y + (frameSize - drawHeight) / 2.0).roundToInt()
            val oldClip = graphics.clip
            graphics.clip(RoundRectangle2D.Double(x + inset, y + inset, box, box, 18.0, 18.0))
            graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null)
            graphics.clip = oldClip
        }
        paintBadge(graphics, x + 4.0, y + 1.0, shape, animation)
    }

    private fun paintShadow(graphics: Graphics2D, bodyX: Double, bodyY: Double, shape: DesktopPetShape) {
        graphics.color = appearance.theme.shadow
        graphics.fill(
            Ellipse2D.Double(
                bodyX + shape.bodyWidth * 0.12,
                bodyY + shape.bodyHeight - 2.0,
                shape.bodyWidth * 0.76,
                8.0,
            ),
        )
    }

    private fun paintTail(
        graphics: Graphics2D,
        bodyX: Double,
        bodyY: Double,
        shape: DesktopPetShape,
        animation: Double,
    ) {
        val swing = if (state.animated) sin(animation * 2.0 * PI) * 5.0 else 0.0
        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val path = Path2D.Double().apply {
            moveTo(bodyX + shape.bodyWidth - 2.0, bodyY + shape.bodyHeight * 0.66)
            curveTo(
                bodyX + shape.bodyWidth + 13.0,
                bodyY + shape.bodyHeight * 0.74,
                bodyX + shape.bodyWidth + 11.0 + swing,
                bodyY + shape.bodyHeight * 0.36,
                bodyX + shape.bodyWidth + 5.0 + swing,
                bodyY + shape.bodyHeight * 0.32,
            )
        }
        graphics.draw(path)
    }

    private fun paintEars(graphics: Graphics2D, bodyX: Double, bodyY: Double, shape: DesktopPetShape) {
        if (shape.earHeight == 0) return
        val baseY = bodyY + 5.0
        val left = Path2D.Double().apply {
            moveTo(bodyX + 7.0, baseY)
            lineTo(bodyX + 15.0, baseY - shape.earHeight)
            lineTo(bodyX + 27.0, baseY + 2.0)
            closePath()
        }
        val right = Path2D.Double().apply {
            moveTo(bodyX + shape.bodyWidth - 27.0, baseY + 2.0)
            lineTo(bodyX + shape.bodyWidth - 15.0, baseY - shape.earHeight)
            lineTo(bodyX + shape.bodyWidth - 7.0, baseY)
            closePath()
        }
        graphics.color = appearance.theme.body
        graphics.fill(left)
        graphics.fill(right)
        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(left)
        graphics.draw(right)
    }

    private fun paintBody(graphics: Graphics2D, bodyX: Double, bodyY: Double, shape: DesktopPetShape) {
        val body = RoundRectangle2D.Double(
            bodyX,
            bodyY,
            shape.bodyWidth.toDouble(),
            shape.bodyHeight.toDouble(),
            shape.cornerRadius.toDouble(),
            shape.cornerRadius.toDouble(),
        )
        graphics.color = appearance.theme.body
        graphics.fill(body)
        graphics.color = appearance.theme.outline
        graphics.stroke = BasicStroke(1.5f)
        graphics.draw(body)

        graphics.color = appearance.theme.accent
        graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Line2D.Double(bodyX + 25.0, bodyY + shape.bodyHeight - 6.0, bodyX + 45.0, bodyY + shape.bodyHeight - 6.0))
    }

    private fun paintCompanionSilhouette(
        graphics: Graphics2D,
        bodyX: Double,
        bodyY: Double,
        shape: DesktopPetShape,
    ) {
        graphics.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (appearance.visual) {
            WorkshopPetVisual.OWL -> {
                val leftWing = Ellipse2D.Double(bodyX - 6.0, bodyY + 25.0, 20.0, 28.0)
                val rightWing = Ellipse2D.Double(bodyX + shape.bodyWidth - 14.0, bodyY + 25.0, 20.0, 28.0)
                graphics.color = appearance.theme.body.darker()
                graphics.fill(leftWing)
                graphics.fill(rightWing)
                graphics.color = appearance.theme.outline
                graphics.draw(leftWing)
                graphics.draw(rightWing)
            }
            WorkshopPetVisual.DUCK -> {
                val wing = Path2D.Double().apply {
                    moveTo(bodyX + 7.0, bodyY + 31.0)
                    curveTo(bodyX - 5.0, bodyY + 35.0, bodyX + 1.0, bodyY + 51.0, bodyX + 18.0, bodyY + 48.0)
                    curveTo(bodyX + 10.0, bodyY + 44.0, bodyX + 10.0, bodyY + 37.0, bodyX + 7.0, bodyY + 31.0)
                    closePath()
                }
                graphics.color = appearance.theme.body.brighter()
                graphics.fill(wing)
                graphics.color = appearance.theme.outline
                graphics.draw(wing)
            }
            WorkshopPetVisual.ROBOT -> {
                val centerX = bodyX + shape.bodyWidth / 2.0
                graphics.color = appearance.theme.outline
                graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                graphics.draw(Line2D.Double(centerX, bodyY, centerX, bodyY - 9.0))
                graphics.color = appearance.theme.accent
                graphics.fill(Ellipse2D.Double(centerX - 3.0, bodyY - 14.0, 6.0, 6.0))
                graphics.color = appearance.theme.outline
                graphics.fill(Ellipse2D.Double(bodyX - 4.0, bodyY + 20.0, 8.0, 15.0))
                graphics.fill(Ellipse2D.Double(bodyX + shape.bodyWidth - 4.0, bodyY + 20.0, 8.0, 15.0))
            }
            else -> Unit
        }
    }

    private fun paintCompanionFeatures(
        graphics: Graphics2D,
        bodyX: Double,
        bodyY: Double,
        shape: DesktopPetShape,
        animation: Double,
    ) {
        val faceX = bodyX + 10.0
        val faceY = bodyY + 11.0
        val faceWidth = shape.bodyWidth - 20.0
        val faceHeight = shape.bodyHeight - 25.0
        val centerX = faceX + faceWidth / 2.0
        when (appearance.visual) {
            WorkshopPetVisual.OWL -> {
                val eyeY = faceY + faceHeight * 0.42
                graphics.color = appearance.theme.outline
                graphics.stroke = BasicStroke(1.3f)
                graphics.draw(Ellipse2D.Double(centerX - 18.0, eyeY - 8.0, 15.0, 15.0))
                graphics.draw(Ellipse2D.Double(centerX + 3.0, eyeY - 8.0, 15.0, 15.0))
                val beak = Path2D.Double().apply {
                    moveTo(centerX - 3.0, eyeY + 7.0)
                    lineTo(centerX + 3.0, eyeY + 7.0)
                    lineTo(centerX, eyeY + 12.0)
                    closePath()
                }
                graphics.color = appearance.theme.accent
                graphics.fill(beak)
            }
            WorkshopPetVisual.DUCK -> {
                val beakY = faceY + faceHeight * 0.62
                val beak = RoundRectangle2D.Double(centerX - 10.0, beakY - 3.0, 20.0, 8.0, 7.0, 7.0)
                graphics.color = appearance.theme.accent
                graphics.fill(beak)
                graphics.color = appearance.theme.outline
                graphics.stroke = BasicStroke(1.2f)
                graphics.draw(beak)
                graphics.draw(Line2D.Double(centerX, beakY - 2.0, centerX, beakY + 3.0))
            }
            WorkshopPetVisual.ROBOT -> {
                val scan = sin(animation * 4.0 * PI) * 3.0
                graphics.color = appearance.theme.accent
                graphics.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                graphics.draw(Line2D.Double(faceX + 8.0, faceY + faceHeight - 5.0 + scan * 0.12, faceX + faceWidth - 8.0, faceY + faceHeight - 5.0 + scan * 0.12))
                graphics.color = appearance.theme.muted
                graphics.fill(Ellipse2D.Double(bodyX + 8.0, bodyY + shape.bodyHeight - 10.0, 4.0, 4.0))
                graphics.fill(Ellipse2D.Double(bodyX + shape.bodyWidth - 12.0, bodyY + shape.bodyHeight - 10.0, 4.0, 4.0))
            }
            else -> Unit
        }
    }

    private fun paintFace(
        graphics: Graphics2D,
        bodyX: Double,
        bodyY: Double,
        shape: DesktopPetShape,
        animation: Double,
    ) {
        val faceX = bodyX + 10.0
        val faceY = bodyY + 11.0
        val faceWidth = shape.bodyWidth - 20.0
        val faceHeight = shape.bodyHeight - 25.0
        graphics.color = appearance.theme.face
        graphics.fill(RoundRectangle2D.Double(faceX, faceY, faceWidth, faceHeight, 14.0, 14.0))

        val leftEyeX = faceX + faceWidth * 0.31
        val rightEyeX = faceX + faceWidth * 0.69
        val eyeY = faceY + faceHeight * 0.42
        val eye = shape.eyeSize.toDouble()
        graphics.color = stateColor()
        graphics.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        when (state) {
            DesktopPetState.IDLE -> {
                val glance = sin(animation * 2.0 * PI) * 1.2
                graphics.fill(Ellipse2D.Double(leftEyeX - eye / 2 + glance, eyeY - eye / 2, eye, eye))
                graphics.fill(Ellipse2D.Double(rightEyeX - eye / 2 + glance, eyeY - eye / 2, eye, eye))
                paintSmile(graphics, faceX, faceY, faceWidth, faceHeight)
            }
            DesktopPetState.THINKING -> {
                val glance = -eye * 0.18
                graphics.fill(Ellipse2D.Double(leftEyeX - eye / 2 + glance, eyeY - eye / 2 - 1.0, eye, eye))
                graphics.fill(Ellipse2D.Double(rightEyeX - eye / 2 + glance, eyeY - eye / 2 - 1.0, eye, eye))
                graphics.draw(Line2D.Double(faceX + faceWidth * 0.42, faceY + faceHeight * 0.72, faceX + faceWidth * 0.58, faceY + faceHeight * 0.72))
            }
            DesktopPetState.TOOL -> {
                graphics.draw(RoundRectangle2D.Double(leftEyeX - eye / 2, eyeY - eye / 2, eye, eye, 2.0, 2.0))
                graphics.draw(RoundRectangle2D.Double(rightEyeX - eye / 2, eyeY - eye / 2, eye, eye, 2.0, 2.0))
                graphics.draw(Line2D.Double(faceX + faceWidth * 0.40, faceY + faceHeight * 0.72, faceX + faceWidth * 0.60, faceY + faceHeight * 0.72))
            }
            DesktopPetState.SUCCESS -> {
                graphics.draw(Arc2D.Double(leftEyeX - eye / 2, eyeY - 1.0, eye, eye * 0.75, 10.0, 160.0, Arc2D.OPEN))
                graphics.draw(Arc2D.Double(rightEyeX - eye / 2, eyeY - 1.0, eye, eye * 0.75, 10.0, 160.0, Arc2D.OPEN))
                paintSmile(graphics, faceX, faceY, faceWidth, faceHeight)
            }
            DesktopPetState.ERROR -> {
                paintCross(graphics, leftEyeX, eyeY, eye)
                paintCross(graphics, rightEyeX, eyeY, eye)
                graphics.draw(Arc2D.Double(faceX + faceWidth * 0.38, faceY + faceHeight * 0.66, faceWidth * 0.24, 8.0, 20.0, 140.0, Arc2D.OPEN))
            }
        }
    }

    private fun paintSmile(graphics: Graphics2D, faceX: Double, faceY: Double, faceWidth: Double, faceHeight: Double) {
        graphics.draw(
            Arc2D.Double(
                faceX + faceWidth * 0.39,
                faceY + faceHeight * 0.58,
                faceWidth * 0.22,
                faceHeight * 0.20,
                200.0,
                140.0,
                Arc2D.OPEN,
            ),
        )
    }

    private fun paintCross(graphics: Graphics2D, centerX: Double, centerY: Double, size: Double) {
        val half = size / 2.0
        graphics.draw(Line2D.Double(centerX - half, centerY - half, centerX + half, centerY + half))
        graphics.draw(Line2D.Double(centerX + half, centerY - half, centerX - half, centerY + half))
    }

    private fun paintBadge(
        graphics: Graphics2D,
        bodyX: Double,
        bodyY: Double,
        shape: DesktopPetShape,
        animation: Double,
    ) {
        val size = shape.badgeSize.toDouble()
        val centerX = bodyX + shape.bodyWidth - 2.0
        val centerY = bodyY + 5.0
        val color = stateColor()
        graphics.color = appearance.theme.face
        graphics.fill(Ellipse2D.Double(centerX - size / 2, centerY - size / 2, size, size))
        graphics.color = color
        graphics.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(Ellipse2D.Double(centerX - size / 2, centerY - size / 2, size, size))

        when (state) {
            DesktopPetState.IDLE -> {
                val pulse = 1.5 + (sin(animation * 2.0 * PI) + 1.0) * 0.7
                graphics.fill(Ellipse2D.Double(centerX - pulse, centerY - pulse, pulse * 2.0, pulse * 2.0))
            }
            DesktopPetState.THINKING -> {
                repeat(3) { index ->
                    val angle = animation * 2.0 * PI + index * 2.0 * PI / 3.0
                    val radius = size * 0.25
                    val x = centerX + cos(angle) * radius
                    val y = centerY + sin(angle) * radius
                    graphics.fill(Ellipse2D.Double(x - 1.5, y - 1.5, 3.0, 3.0))
                }
            }
            DesktopPetState.TOOL -> {
                val terminalWidth = size * 0.56
                val terminalHeight = size * 0.44
                graphics.draw(
                    RoundRectangle2D.Double(
                        centerX - terminalWidth / 2,
                        centerY - terminalHeight / 2,
                        terminalWidth,
                        terminalHeight,
                        2.0,
                        2.0,
                    ),
                )
                graphics.draw(Line2D.Double(centerX - 3.5, centerY - 2.0, centerX - 0.5, centerY))
                graphics.draw(Line2D.Double(centerX - 0.5, centerY, centerX - 3.5, centerY + 2.0))
                graphics.draw(Line2D.Double(centerX + 1.5, centerY + 2.5, centerX + 4.5, centerY + 2.5))
            }
            DesktopPetState.SUCCESS -> {
                val path = Path2D.Double().apply {
                    moveTo(centerX - 5.0, centerY)
                    lineTo(centerX - 1.0, centerY + 4.0)
                    lineTo(centerX + 6.0, centerY - 5.0)
                }
                graphics.draw(path)
            }
            DesktopPetState.ERROR -> {
                graphics.draw(Line2D.Double(centerX, centerY - 5.0, centerX, centerY + 2.0))
                graphics.fill(Ellipse2D.Double(centerX - 1.2, centerY + 4.5, 2.4, 2.4))
            }
        }
    }

    private fun paintStatus(graphics: Graphics2D, shape: DesktopPetShape) {
        val label = state.accessibleLabel
        val color = stateColor()
        val fontSize = (UIManager.getFont("Label.font")?.size2D ?: 12f).coerceIn(10f, 14f)
        graphics.font = graphics.font.deriveFont(Font.BOLD, fontSize)
        val metrics = graphics.fontMetrics
        val x = ((shape.preferredWidth - metrics.stringWidth(label)) / 2.0).roundToInt()
        val y = shape.preferredHeight - 7
        graphics.color = color
        graphics.drawString(label, x, y)
    }

    private fun stateColor(): Color = when (state) {
        DesktopPetState.SUCCESS -> appearance.theme.success
        DesktopPetState.ERROR -> appearance.theme.error
        DesktopPetState.IDLE -> appearance.theme.muted
        DesktopPetState.THINKING, DesktopPetState.TOOL -> appearance.theme.accent
    }

    private fun animationFraction(): Double = phase.toDouble() / FRAME_COUNT

    private companion object {
        const val FRAME_DELAY_MS = 90
        const val FRAME_COUNT = 40
        const val MINIMUM_SIZE = 64
        const val INTERACTIVE_INSET = 8
        const val STATUS_LABEL_HEIGHT = 15
    }
}

private fun defaultPetPlacementSettings(): WorkshopSettingsService? =
    runCatching { WorkshopSettingsService.getInstance() }.getOrNull()
