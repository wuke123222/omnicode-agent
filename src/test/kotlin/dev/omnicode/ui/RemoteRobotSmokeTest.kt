package dev.omnicode.ui

import com.intellij.remoterobot.RemoteRobot
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Runs only when a desktop CI job supplies OMNICODE_REMOTE_ROBOT_URL. The normal unit-test task
 * stays headless; the UI workflow starts the IDE with Robot Server and points this client at it.
 */
class RemoteRobotSmokeTest {
    @Test
    fun `remote robot can capture a real IDE desktop`() {
        val endpoint = System.getenv("OMNICODE_REMOTE_ROBOT_URL")?.trim().orEmpty()
        if (endpoint.isBlank()) return
        require(endpoint.startsWith("http://127.0.0.1:") || endpoint.startsWith("http://localhost:")) {
            "Remote Robot endpoint must be a loopback URL in CI"
        }
        val robot = RemoteRobot(endpoint)
        val screenshot = robot.getScreenshot()
        assertNotNull(screenshot)
        require(screenshot.width >= 640 && screenshot.height >= 480) {
            "Remote Robot returned an unexpectedly small desktop screenshot"
        }
        // Exercise the HTTP client again with a bounded native query, proving that the test is
        // talking to Robot Server rather than merely constructing a client object.
        require(robot.os.isNotBlank())
    }
}
