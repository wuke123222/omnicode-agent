package dev.omnicode.commercial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class OmniCodeEntitlementsTest {
    private val now = Instant.parse("2026-08-05T00:00:00Z")
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = OmniCodeLicenseVerifier(
        publicKey = keyPair.public,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `valid signed license unlocks the declared plan`() {
        val token = signed("""
            {"product":"omnicode-agent","plan":"PRO","subject":"acct-123","issuedAt":${now.epochSecond},"expiresAt":${now.plusSeconds(3600).epochSecond}}
        """.trimIndent())

        val entitlement = verifier.verify(token)

        assertEquals(OmniCodePlan.PRO, entitlement.plan)
        assertTrue(entitlement.allows(OmniCodePaidFeature.PROJECT_INTELLIGENCE_DOSSIER))
        assertTrue(entitlement.allows(OmniCodePaidFeature.BATCH_TASK_RECIPES))
        assertTrue(entitlement.allows(OmniCodePaidFeature.ENGINEERING_WEEKLY_DIGEST))
        assertTrue(!entitlement.allows(OmniCodePaidFeature.RESEARCH_LOCKED_EXPORT))
    }

    @Test
    fun `tampering or expiry is rejected`() {
        val token = signed("{\"product\":\"omnicode-agent\",\"plan\":\"PRO\",\"expiresAt\":${now.plusSeconds(3600).epochSecond}}")
        val signature = token.substringAfterLast('.')
        val changedSignature = (if (signature.first() == 'A') 'B' else 'A') + signature.drop(1)
        val tampered = token.substringBeforeLast('.') + "." + changedSignature
        assertFailsWith<IllegalArgumentException> { verifier.verify(tampered) }

        val expired = signed("{\"product\":\"omnicode-agent\",\"plan\":\"PRO\",\"expiresAt\":${now.minusSeconds(1).epochSecond}}")
        assertFailsWith<IllegalArgumentException> { verifier.verify(expired) }
        assertEquals(OmniCodePlan.FREE, verifier.verifyOrFree(tampered).plan)
    }

    private fun signed(payload: String): String {
        val payloadPart = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val input = "${OmniCodeLicenseVerifier.TOKEN_PREFIX}.$payloadPart"
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(input.toByteArray(StandardCharsets.US_ASCII))
        }.sign()
        val signaturePart = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return "$input.$signaturePart"
    }
}
