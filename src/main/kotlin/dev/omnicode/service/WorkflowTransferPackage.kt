package dev.omnicode.service

import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.persistence.PersistenceJson
import dev.omnicode.persistence.SensitiveDataRedactor
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted, bounded task hand-off package for moving a recoverable workflow between machines.
 *
 * The package contains only the redacted textual checkpoint. It never includes API keys, model
 * credentials, images, PDFs, a repository snapshot, process environment, or provider history.
 * The passphrase is used only in memory and is never serialized.
 */
class WorkflowTransferPackage(
    private val redactor: SensitiveDataRedactor = DefaultSensitiveDataRedactor(),
) {
    fun export(
        checkpoint: WorkflowCheckpoint,
        passphrase: CharArray,
        sourceProjectFingerprint: String,
    ): ByteArray {
        validatePassphrase(passphrase)
        require(sourceProjectFingerprint.length in 1..MAX_FINGERPRINT_CHARS) {
            "A bounded source project fingerprint is required."
        }
        require(checkpoint.workflowId.isNotBlank() && checkpoint.runId.isNotBlank()) {
            "Workflow checkpoint identity is missing."
        }
        require(checkpoint.requiredImageAttachments == 0) {
            "This checkpoint requires image attachments; reattach them before exporting."
        }

        val redactedJson = redactor.redact(PersistenceJson.gson.toJson(checkpoint))
        val payload = redactedJson.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Workflow transfer payload is too large." }

        val salt = ByteArray(SALT_BYTES).also(SECURE_RANDOM::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SECURE_RANDOM::nextBytes)
        val aad = authenticatedHeader(sourceProjectFingerprint, checkpoint.workflowId)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce, aad).doFinal(payload)
        val envelope = Envelope(
            formatVersion = FORMAT_VERSION,
            createdAt = Instant.now(),
            sourceProjectFingerprint = sourceProjectFingerprint,
            workflowId = checkpoint.workflowId,
            salt = ENCODER.encodeToString(salt),
            nonce = ENCODER.encodeToString(nonce),
            ciphertext = ENCODER.encodeToString(encrypted),
        )
        val result = PersistenceJson.gson.toJson(envelope).toByteArray(StandardCharsets.UTF_8)
        require(result.size <= MAX_PACKAGE_BYTES) { "Workflow transfer package is too large." }
        return result
    }

    /**
     * Decrypts and validates a package. The returned checkpoint is assigned fresh workflow/run
     * IDs and the target project id; this prevents an import from overwriting a local task and
     * makes the imported task require an explicit recovery action.
     */
    fun import(
        packageBytes: ByteArray,
        passphrase: CharArray,
        targetProjectId: String,
        expectedSourceProjectFingerprint: String? = null,
    ): WorkflowCheckpoint {
        validatePassphrase(passphrase)
        require(targetProjectId.isNotBlank() && targetProjectId.length <= MAX_FINGERPRINT_CHARS) {
            "A bounded target project id is required."
        }
        require(packageBytes.size in 1..MAX_PACKAGE_BYTES) { "Workflow transfer package is too large." }
        val envelope = parseEnvelope(packageBytes)
        if (expectedSourceProjectFingerprint != null) {
            require(envelope.sourceProjectFingerprint == expectedSourceProjectFingerprint) {
                "Workflow package belongs to a different source project."
            }
        }

        val salt = decodeBounded(envelope.salt, SALT_BYTES)
        val nonce = decodeBounded(envelope.nonce, NONCE_BYTES)
        val encrypted = decodeBounded(envelope.ciphertext, MAX_PAYLOAD_BYTES + GCM_TAG_BYTES)
        val aad = authenticatedHeader(envelope.sourceProjectFingerprint, envelope.workflowId)
        val payload = try {
            cipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce, aad).doFinal(encrypted)
        } catch (_: AEADBadTagException) {
            throw SecurityException("Invalid workflow passphrase or tampered package.")
        } catch (error: GeneralSecurityException) {
            throw SecurityException("Unable to decrypt workflow package.", error)
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Workflow transfer payload is too large." }

        val checkpoint = runCatching {
            PersistenceJson.gson.fromJson(payload.toString(StandardCharsets.UTF_8), WorkflowCheckpoint::class.java)
        }.getOrElse { error ->
            throw IllegalArgumentException("Workflow package payload is invalid.", error)
        } ?: throw IllegalArgumentException("Workflow package payload is empty.")
        require(checkpoint.workflowId == envelope.workflowId) { "Workflow package identity is inconsistent." }
        require(checkpoint.requiredImageAttachments == 0) {
            "This checkpoint requires image attachments and cannot be resumed from a package alone."
        }
        return checkpoint.copy(
            workflowId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            projectId = targetProjectId,
            state = WorkflowCheckpointState.INTERRUPTED,
            updatedAt = Instant.now(),
        )
    }

    private fun parseEnvelope(bytes: ByteArray): Envelope {
        val raw = bytes.toString(StandardCharsets.UTF_8)
        require(raw.length <= MAX_PACKAGE_BYTES) { "Workflow transfer package is too large." }
        val envelope = runCatching {
            PersistenceJson.gson.fromJson(raw, Envelope::class.java)
        }.getOrElse { error ->
            throw IllegalArgumentException("Workflow package envelope is invalid.", error)
        } ?: throw IllegalArgumentException("Workflow package envelope is empty.")
        require(envelope.formatVersion == FORMAT_VERSION) { "Unsupported workflow package version." }
        require(envelope.workflowId.isNotBlank() && envelope.workflowId.length <= MAX_ID_CHARS)
        require(envelope.sourceProjectFingerprint.isNotBlank() && envelope.sourceProjectFingerprint.length <= MAX_FINGERPRINT_CHARS)
        return envelope
    }

    private fun decodeBounded(value: String, exactBytes: Int): ByteArray {
        require(value.isNotBlank() && value.length <= exactBytes * 2 + 64) { "Invalid workflow package field." }
        val decoded = runCatching { DECODER.decode(value) }
            .getOrElse { throw IllegalArgumentException("Invalid workflow package encoding.", it) }
        require(decoded.size == exactBytes || exactBytes > SALT_BYTES && decoded.size <= exactBytes) {
            "Invalid workflow package field length."
        }
        return decoded
    }

    private fun cipher(
        mode: Int,
        passphrase: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            key.fill(0)
        }
    }

    private fun authenticatedHeader(fingerprint: String, workflowId: String): ByteArray =
        "omnicode-workflow-v$FORMAT_VERSION\u0000$fingerprint\u0000$workflowId"
            .toByteArray(StandardCharsets.UTF_8)

    private fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Use a passphrase between $MIN_PASSPHRASE_CHARS and $MAX_PASSPHRASE_CHARS characters."
        }
    }

    private data class Envelope(
        val formatVersion: Int,
        val createdAt: Instant,
        val sourceProjectFingerprint: String,
        val workflowId: String,
        val salt: String,
        val nonce: String,
        val ciphertext: String,
    )

    private companion object {
        const val FORMAT_VERSION = 1
        const val MIN_PASSPHRASE_CHARS = 12
        const val MAX_PASSPHRASE_CHARS = 256
        const val MAX_ID_CHARS = 256
        const val MAX_FINGERPRINT_CHARS = 256
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_PACKAGE_BYTES = 2 * 1_048_576
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        const val KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 120_000
        val SECURE_RANDOM = SecureRandom()
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
