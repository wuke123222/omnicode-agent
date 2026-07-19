package dev.omnicode.provider

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

internal object AwsSigV4 {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun signPost(
        url: String,
        body: String,
        headers: Map<String, String>,
        region: String,
        service: String,
        credentials: AwsCredentials,
        clock: Clock = Clock.systemUTC(),
    ): Map<String, String> {
        val uri = URI.create(url)
        val now = clock.instant()
        val amzDate = dateTimeFormatter.format(now)
        val dateStamp = dateFormatter.format(now)
        val payloadHash = sha256Hex(body.toByteArray(StandardCharsets.UTF_8))

        val signable = linkedMapOf<String, String>()
        headers.forEach { (name, value) ->
            val normalizedName = name.lowercase()
            if (normalizedName !in setOf("authorization", "host", "user-agent", "content-length")) {
                signable[normalizedName] = normalizeHeader(value)
            }
        }
        signable["host"] = uri.rawAuthority
        signable["x-amz-content-sha256"] = payloadHash
        signable["x-amz-date"] = amzDate
        credentials.sessionToken?.takeIf { it.isNotBlank() }?.let {
            signable["x-amz-security-token"] = normalizeHeader(it)
        }

        val sortedHeaders = signable.toSortedMap()
        val canonicalHeaders = sortedHeaders.entries.joinToString(separator = "", postfix = "") {
            "${it.key}:${it.value}\n"
        }
        val signedHeaders = sortedHeaders.keys.joinToString(";")
        val canonicalRequest = listOf(
            "POST",
            uri.rawPath.ifBlank { "/" },
            canonicalQuery(uri.rawQuery),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)),
        ).joinToString("\n")
        val signingKey = hmac(
            hmac(
                hmac(
                    hmac(("AWS4" + credentials.secretAccessKey).toByteArray(StandardCharsets.UTF_8), dateStamp),
                    region,
                ),
                service,
            ),
            "aws4_request",
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return buildMap {
            headers.forEach { (name, value) ->
                if (!name.equals("host", true) && !name.equals("authorization", true)) put(name, value)
            }
            put("x-amz-content-sha256", payloadHash)
            put("x-amz-date", amzDate)
            credentials.sessionToken?.takeIf { it.isNotBlank() }?.let { put("x-amz-security-token", it) }
            put("Authorization", authorization)
        }
    }

    private fun canonicalQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery.split('&')
            .map { component ->
                val name = component.substringBefore('=')
                val value = component.substringAfter('=', "")
                name to value
            }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun normalizeHeader(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
