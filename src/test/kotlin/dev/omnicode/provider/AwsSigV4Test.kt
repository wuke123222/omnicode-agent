package dev.omnicode.provider

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AwsSigV4Test {
    @Test
    fun `signature is deterministic and never embeds the secret key`() {
        val credentials = AwsCredentials("AKIDEXAMPLE", "secret-value", "session-token")
        val headers = AwsSigV4.signPost(
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/example/converse",
            body = "{\"messages\":[]}",
            headers = mapOf("Content-Type" to "application/json"),
            region = "us-east-1",
            service = "bedrock",
            credentials = credentials,
            clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC),
        )

        assertEquals("20260102T030405Z", headers["x-amz-date"])
        assertEquals("session-token", headers["x-amz-security-token"])
        assertTrue(headers.getValue("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260102/us-east-1/bedrock/aws4_request"))
        assertFalse(headers.values.any { it.contains("secret-value") })
    }
}
