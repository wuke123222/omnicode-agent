package dev.omnicode.commercial

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

/** Product tiers are ordered so a higher tier automatically includes lower-tier features. */
enum class OmniCodePlan(val displayName: String, internal val rank: Int) {
    FREE("Free", 0),
    PRO("Pro", 1),
    TEAM("Team", 2),
    RESEARCH("Research", 3),
    ;

    companion object {
        fun parse(value: String): OmniCodePlan? = entries.firstOrNull {
            it.name.equals(value.trim(), ignoreCase = true)
        }
    }
}

/** Optional features that can be commercialized without restricting the free coding loop. */
enum class OmniCodePaidFeature(
    val displayName: String,
    val minimumPlan: OmniCodePlan,
    val description: String,
) {
    PROJECT_INTELLIGENCE_DOSSIER(
        "项目智能档案",
        OmniCodePlan.PRO,
        "生成可分享的架构、规则、上下文占用和风险摘要，帮助团队快速接手项目。",
    ),
    BATCH_TASK_RECIPES(
        "批量任务配方",
        OmniCodePlan.PRO,
        "把任务目标和运行偏好保存为可复用配方，便于在多个项目中重新发起。",
    ),
    ENGINEERING_WEEKLY_DIGEST(
        "工程进展周报",
        OmniCodePlan.PRO,
        "按本地 Git 版本差异、提交和 OmniCode 任务账本生成可直接发送的周报。",
    ),
    RESEARCH_LOCKED_EXPORT(
        "研究复现实验包",
        OmniCodePlan.PRO,
        "把实验锁定、依赖摘要和有界证据编入可复现研究包。",
    ),
}

data class OmniCodeEntitlement(
    val plan: OmniCodePlan,
    val subject: String? = null,
    val issuedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val source: EntitlementSource = EntitlementSource.NONE,
) {
    fun allows(feature: OmniCodePaidFeature): Boolean = plan.rank >= feature.minimumPlan.rank

    fun displayLabel(now: Instant = Instant.now()): String = when {
        source == EntitlementSource.LOCAL_PREVIEW -> "本地预览 · ${plan.displayName}"
        source == EntitlementSource.JETBRAINS_MARKETPLACE -> "JetBrains Marketplace · ${plan.displayName}"
        plan == OmniCodePlan.FREE -> "Free"
        expiresAt == null -> plan.displayName
        expiresAt.isAfter(now) -> "${plan.displayName} · 有效至 ${expiresAt}"
        else -> "${plan.displayName} · 已过期"
    }
}

enum class EntitlementSource { NONE, JETBRAINS_MARKETPLACE, SIGNED_LICENSE, LOCAL_PREVIEW }

data class FeatureAccess(
    val feature: OmniCodePaidFeature,
    val entitlement: OmniCodeEntitlement,
    val allowed: Boolean,
    val message: String,
) {
    val requiresUpgrade: Boolean get() = !allowed
}

// SubjectPublicKeyInfo DER for vendor Ed25519 verification keys. The first key signs new billing
// licenses; the legacy key remains verification-only so existing manually issued tokens survive
// the rotation. Never accept a key supplied by project files, Harness configuration or the model.
private const val DEFAULT_PUBLIC_KEY_DER_BASE64 =
    "MCowBQYDK2VwAyEABAxYpRytUodzP9mgv10mpRjfQzyniNMSPF/FbLXO1ao="
private const val LEGACY_PUBLIC_KEY_DER_BASE64 =
    "MCowBQYDK2VwAyEAd7YdH0Txpvl96hcko+7Kwnu42TFBgClk1I6vTECqYto="

private fun licensePublicKey(encoded: String): PublicKey = KeyFactory.getInstance("Ed25519")
    .generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(encoded)),
    )

private fun defaultLicensePublicKey(): PublicKey = licensePublicKey(DEFAULT_PUBLIC_KEY_DER_BASE64)

private fun defaultFallbackPublicKeys(primary: PublicKey): List<PublicKey> =
    if (Base64.getEncoder().encodeToString(primary.encoded) == DEFAULT_PUBLIC_KEY_DER_BASE64) {
        listOf(licensePublicKey(LEGACY_PUBLIC_KEY_DER_BASE64))
    } else {
        emptyList()
    }

/**
 * Verifies the vendor-issued offline license format without contacting an arbitrary endpoint.
 *
 * Format: `omnicode-v1.<base64url(payload-json)>.<base64url(ed25519-signature)>`.
 * The private signing key never belongs in the plugin repository; only the vendor public key
 * is shipped here. A malformed, expired or incorrectly signed token always resolves to Free.
 */
class OmniCodeLicenseVerifier(
    private val publicKey: PublicKey = defaultLicensePublicKey(),
    private val clock: Clock = Clock.systemUTC(),
    private val fallbackPublicKeys: List<PublicKey> = defaultFallbackPublicKeys(publicKey),
) {
    fun verify(rawToken: String): OmniCodeEntitlement {
        val token = rawToken.trim()
        require(token.length in 32..MAX_TOKEN_CHARS) { "许可证格式无效。" }
        val parts = token.split('.')
        require(parts.size == 3 && parts[0] == TOKEN_PREFIX) { "许可证格式无效。" }
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)
        val payloadBytes = decode(parts[1], "许可证载荷无效。")
        val signatureBytes = decode(parts[2], "许可证签名无效。")
        require(signatureBytes.size == SIGNATURE_BYTES) { "许可证签名长度无效。" }
        val signatureValid = (listOf(publicKey) + fallbackPublicKeys).any { verificationKey ->
            runCatching {
                Signature.getInstance("Ed25519").run {
                    initVerify(verificationKey)
                    update(signingInput)
                    verify(signatureBytes)
                }
            }.getOrDefault(false)
        }
        require(signatureValid) { "许可证签名校验失败。" }

        val payload = runCatching { JsonParser.parseString(payloadBytes.toString(StandardCharsets.UTF_8)).asJsonObject }
            .getOrElse { throw IllegalArgumentException("许可证载荷无效。", it) }
        require(payload.string("product") == PRODUCT_ID) { "许可证产品不匹配。" }
        val plan = OmniCodePlan.parse(payload.string("plan"))
            ?: throw IllegalArgumentException("许可证套餐无效。")
        val subject = payload.string("subject").trim().takeIf(String::isNotBlank)
        require(subject == null || subject.length <= MAX_SUBJECT_CHARS) { "许可证主体过长。" }
        val issuedAt = payload.epoch("issuedAt")
        val expiresAt = payload.epoch("expiresAt")
        require(expiresAt == null || expiresAt.isAfter(issuedAt ?: Instant.EPOCH)) {
            "许可证有效期无效。"
        }
        val now = clock.instant()
        require(issuedAt == null || !issuedAt.isAfter(now.plusSeconds(ALLOWED_CLOCK_SKEW_SECONDS))) {
            "许可证尚未生效。"
        }
        require(expiresAt == null || expiresAt.isAfter(now)) { "许可证已过期。" }
        return OmniCodeEntitlement(plan, subject, issuedAt, expiresAt, EntitlementSource.SIGNED_LICENSE)
    }

    fun verifyOrFree(rawToken: String): OmniCodeEntitlement = runCatching { verify(rawToken) }
        .getOrDefault(OmniCodeEntitlement(OmniCodePlan.FREE))

    private fun decode(value: String, message: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException(message, it) }

    private fun com.google.gson.JsonObject.string(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun com.google.gson.JsonObject.epoch(name: String): Instant? =
        get(name)?.takeIf { !it.isJsonNull }?.asLong?.let(Instant::ofEpochSecond)

    companion object {
        const val TOKEN_PREFIX = "omnicode-v1"
        const val PRODUCT_ID = "omnicode-agent"
        const val MAX_TOKEN_CHARS = 8_192
        private const val MAX_SUBJECT_CHARS = 256
        private const val SIGNATURE_BYTES = 64
        private const val ALLOWED_CLOCK_SKEW_SECONDS = 300L

    }
}

@Service(Service.Level.APP)
class OmniCodeLicenseStore {
    fun load(): String = PasswordSafe.instance.getPassword(attributes()).orEmpty()

    fun save(token: String) {
        PasswordSafe.instance.setPassword(attributes(), token.trim().takeIf(String::isNotEmpty))
    }

    fun clear() = PasswordSafe.instance.setPassword(attributes(), null)

    private fun attributes(): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, "signed-license"),
    )

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.commercial"

        fun getInstance(): OmniCodeLicenseStore =
            ApplicationManager.getApplication().getService(OmniCodeLicenseStore::class.java)
    }
}

@Service(Service.Level.APP)
class OmniCodeEntitlementService(
    private val licenseStore: OmniCodeLicenseStore = OmniCodeLicenseStore.getInstance(),
    private val verifier: OmniCodeLicenseVerifier = OmniCodeLicenseVerifier(),
    private val marketplaceLicense: OmniCodeMarketplaceLicense = OmniCodeMarketplaceLicense(),
) {
    @Volatile
    private var cached: OmniCodeEntitlement? = null

    fun current(): OmniCodeEntitlement {
        if (localPreviewEnabled()) {
            return cached?.takeIf { it.source == EntitlementSource.LOCAL_PREVIEW } ?: synchronized(this) {
                cached?.takeIf { it.source == EntitlementSource.LOCAL_PREVIEW } ?: OmniCodeEntitlement(
                    plan = OmniCodePlan.RESEARCH,
                    subject = "local-preview",
                    source = EntitlementSource.LOCAL_PREVIEW,
                ).also { cached = it }
            }
        }

        return when (marketplaceLicense.status()) {
            MarketplaceLicenseStatus.LICENSED -> OmniCodeEntitlement(
                plan = OmniCodePlan.PRO,
                subject = "jetbrains-marketplace",
                source = EntitlementSource.JETBRAINS_MARKETPLACE,
            ).also { cached = it }

            MarketplaceLicenseStatus.INITIALIZING -> cached
                ?.takeIf { it.source == EntitlementSource.JETBRAINS_MARKETPLACE }
                ?: signedEntitlement()

            MarketplaceLicenseStatus.UNLICENSED -> signedEntitlement()
        }
    }

    private fun signedEntitlement(): OmniCodeEntitlement = cached
        ?.takeIf { it.source == EntitlementSource.SIGNED_LICENSE || it.source == EntitlementSource.NONE }
        ?: synchronized(this) {
            cached?.takeIf { it.source == EntitlementSource.SIGNED_LICENSE || it.source == EntitlementSource.NONE }
                ?: verifier.verifyOrFree(licenseStore.load()).also { cached = it }
        }

    fun refreshMarketplace(): OmniCodeEntitlement {
        marketplaceLicense.invalidate()
        synchronized(this) { cached = null }
        return current()
    }

    fun requestMarketplaceLicense(message: String) {
        marketplaceLicense.requestLicense(message)
    }

    fun marketplaceStatus(): MarketplaceLicenseStatus = marketplaceLicense.status()

    fun access(feature: OmniCodePaidFeature): FeatureAccess {
        val entitlement = current()
        val allowed = entitlement.allows(feature)
        return FeatureAccess(
            feature = feature,
            entitlement = entitlement,
            allowed = allowed,
            message = if (allowed && entitlement.source == EntitlementSource.LOCAL_PREVIEW) {
                "${feature.displayName} 已在本地预览模式解锁；不会影响 Marketplace 用户。"
            } else if (allowed && entitlement.source == EntitlementSource.JETBRAINS_MARKETPLACE) {
                "${feature.displayName} 已由 JetBrains Marketplace Pro 权益解锁。"
            } else if (allowed) {
                "${feature.displayName} 已由 ${entitlement.plan.displayName} 权益解锁。"
            } else {
                "${feature.displayName} 需要 ${feature.minimumPlan.displayName} 计划；当前为 ${entitlement.plan.displayName}。"
            },
        )
    }

    @Synchronized
    fun activate(rawToken: String): OmniCodeEntitlement {
        val verified = verifier.verify(rawToken)
        licenseStore.save(rawToken)
        cached = verified
        return verified
    }

    @Synchronized
    fun clear() {
        licenseStore.clear()
        cached = OmniCodeEntitlement(OmniCodePlan.FREE)
    }

    fun invalidateCache() {
        synchronized(this) { cached = null }
        marketplaceLicense.invalidate()
    }

    companion object {
        /** Only the local Gradle `runIde` task sets this alongside IntelliJ internal mode. */
        private fun localPreviewEnabled(): Boolean =
            java.lang.Boolean.getBoolean("omnicode.localPreview") &&
                java.lang.Boolean.getBoolean("idea.is.internal")

        fun getInstance(): OmniCodeEntitlementService =
            ApplicationManager.getApplication().getService(OmniCodeEntitlementService::class.java)
    }
}
