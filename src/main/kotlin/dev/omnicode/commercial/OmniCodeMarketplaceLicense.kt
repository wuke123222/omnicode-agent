package dev.omnicode.commercial

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.LicensingFacade

enum class MarketplaceLicenseStatus {
    INITIALIZING,
    LICENSED,
    UNLICENSED,
}

internal data class MarketplaceConfirmation(
    val initialized: Boolean,
    val stamp: String?,
)

internal fun interface MarketplaceConfirmationSource {
    fun read(): MarketplaceConfirmation
}

/**
 * Bounded bridge to the IDE-managed JetBrains Marketplace license state.
 *
 * JetBrains owns checkout, account activation and daily license refresh. OmniCode only reads and
 * verifies the confirmation stamp for its fixed product code. Checks are cached because validating
 * a certificate chain on every feature lookup would waste CPU.
 */
class OmniCodeMarketplaceLicense internal constructor(
    private val source: MarketplaceConfirmationSource = MarketplaceConfirmationSource {
        val facade = LicensingFacade.getInstance()
        MarketplaceConfirmation(
            initialized = facade != null,
            stamp = facade?.getConfirmationStamp(PRODUCT_CODE),
        )
    },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val verifier: (String) -> Boolean = JetBrainsLicenseStampVerifier::isValid,
) {
    @Volatile
    private var cached: CachedMarketplaceStatus? = null

    fun status(force: Boolean = false): MarketplaceLicenseStatus {
        val now = clockMillis()
        cached?.takeIf { !force && now < it.refreshAfterMillis }?.let { return it.status }
        return synchronized(this) {
            cached?.takeIf { !force && now < it.refreshAfterMillis }?.status ?: run {
                val confirmation = runCatching(source::read).getOrNull()
                val status = when {
                    confirmation == null || !confirmation.initialized -> MarketplaceLicenseStatus.INITIALIZING
                    confirmation.stamp.isNullOrBlank() -> MarketplaceLicenseStatus.UNLICENSED
                    verifier(confirmation.stamp) -> MarketplaceLicenseStatus.LICENSED
                    else -> MarketplaceLicenseStatus.UNLICENSED
                }
                val ttl = if (status == MarketplaceLicenseStatus.INITIALIZING) {
                    INITIALIZATION_RETRY_MILLIS
                } else {
                    LICENSE_CHECK_TTL_MILLIS
                }
                status.also { cached = CachedMarketplaceStatus(it, now + ttl) }
            }
        }
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    /** Opens the IDE's own trial/purchase/activation surface for the fixed OmniCode product. */
    fun requestLicense(message: String) {
        val boundedMessage = message.trim().take(MAX_MESSAGE_CHARS)
        ApplicationManager.getApplication().invokeLater({
            val action = ActionManager.getInstance().getAction("RegisterPlugins")
                ?: ActionManager.getInstance().getAction("Register")
                ?: return@invokeLater
            val context = DataContext { dataId ->
                when (dataId) {
                    "register.product-descriptor.code" -> PRODUCT_CODE
                    "register.message" -> boundedMessage.takeIf(String::isNotBlank)
                    else -> null
                }
            }
            ActionUtil.performAction(
                action,
                AnActionEvent.createEvent(
                    context,
                    Presentation(),
                    "OmniCode.Pro",
                    ActionUiKind.NONE,
                    null,
                ),
            )
        }, ModalityState.nonModal())
    }

    private data class CachedMarketplaceStatus(
        val status: MarketplaceLicenseStatus,
        val refreshAfterMillis: Long,
    )

    companion object {
        const val PRODUCT_CODE = "POMNICODE"
        private const val INITIALIZATION_RETRY_MILLIS = 10_000L
        private const val LICENSE_CHECK_TTL_MILLIS = 6 * 60 * 60 * 1_000L
        private const val MAX_MESSAGE_CHARS = 500
    }
}
