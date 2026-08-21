package dev.omnicode.commercial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OmniCodeMarketplaceLicenseTest {
    @Test
    fun `uninitialized facade is not treated as an unlicensed account`() {
        val license = OmniCodeMarketplaceLicense(
            source = MarketplaceConfirmationSource { MarketplaceConfirmation(false, null) },
        )

        assertEquals(MarketplaceLicenseStatus.INITIALIZING, license.status())
    }

    @Test
    fun `only a verified confirmation stamp unlocks pro`() {
        var verifierCalls = 0
        val license = OmniCodeMarketplaceLicense(
            source = MarketplaceConfirmationSource { MarketplaceConfirmation(true, "key:signed") },
            verifier = {
                verifierCalls++
                it == "key:signed"
            },
        )

        assertEquals(MarketplaceLicenseStatus.LICENSED, license.status())
        assertEquals(MarketplaceLicenseStatus.LICENSED, license.status())
        assertEquals(1, verifierCalls, "certificate verification should be cached")
    }

    @Test
    fun `missing or malformed marketplace stamps fail closed`() {
        val missing = OmniCodeMarketplaceLicense(
            source = MarketplaceConfirmationSource { MarketplaceConfirmation(true, null) },
        )

        assertEquals(MarketplaceLicenseStatus.UNLICENSED, missing.status())
        assertFalse(JetBrainsLicenseStampVerifier.isValid(""))
        assertFalse(JetBrainsLicenseStampVerifier.isValid("key:not-a-license"))
        assertFalse(JetBrainsLicenseStampVerifier.isValid("unknown:payload"))
    }

    @Test
    fun `explicit refresh observes a changed JetBrains account`() {
        var stamp: String? = null
        val license = OmniCodeMarketplaceLicense(
            source = MarketplaceConfirmationSource { MarketplaceConfirmation(true, stamp) },
            verifier = { it == "key:valid" },
        )
        assertEquals(MarketplaceLicenseStatus.UNLICENSED, license.status())

        stamp = "key:valid"

        assertEquals(MarketplaceLicenseStatus.LICENSED, license.status(force = true))
    }
}
