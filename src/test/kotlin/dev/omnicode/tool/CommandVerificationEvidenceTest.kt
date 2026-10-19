package dev.omnicode.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandVerificationEvidenceTest {
    @Test
    fun `recognizes direct validation argv without exposing arbitrary arguments`() {
        assertEquals("Gradle test/check", recognizedValidationCommand(listOf("./gradlew", "--offline", ":app:test")))
        assertEquals("Maven test/verify", recognizedValidationCommand(listOf("mvn", "verify")))
        assertEquals("NPM test", recognizedValidationCommand(listOf("npm.cmd", "run", "test")))
        assertEquals("pytest", recognizedValidationCommand(listOf("/usr/bin/pytest", "tests")))
        assertNull(recognizedValidationCommand(listOf("bash", "-lc", "npm test")))
        assertNull(recognizedValidationCommand(listOf("npm", "run", "deploy")))
    }
}
