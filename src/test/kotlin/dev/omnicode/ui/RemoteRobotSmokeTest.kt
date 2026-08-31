package dev.omnicode.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val panel = findOmniCodePanel(robot)
        assertTrue(panel.isShowing, "OmniCode Tool Window did not become visible")
        require(panel.getScreenshot().width >= 300) { "OmniCode Tool Window is unexpectedly narrow" }

        // Title actions are native Swing controls even though the content is JCEF. Exercising
        // them verifies the real click path and the bridge-backed three-view navigation.
        findAny(robot, Duration.ofSeconds(12),
            "//div[@tooltiptext='设置']",
            "//div[@accessiblename='设置']",
            "//div[@text='设置']",
        ).click()
        Thread.sleep(500)
        // Persist the real desktop frame when the CI job requests it. The workflow uploads this
        // artifact for human review; local/headless unit runs never write an implicit file.
        System.getenv("OMNICODE_REMOTE_ROBOT_SCREENSHOT")?.trim()?.takeIf(String::isNotBlank)?.let { path ->
            val target = File(path).canonicalFile
            target.parentFile?.mkdirs()
            check(ImageIO.write(robot.getScreenshot(), "png", target)) { "PNG screenshot writer is unavailable" }
            require(target.length() in 1..8_000_000) { "Remote Robot screenshot exceeded the evidence bound" }
        }
        // Exercise the HTTP client again with a bounded native query, proving that the test is
        // talking to Robot Server rather than merely constructing a client object.
        require(robot.os.isNotBlank())
    }

    private fun findOmniCodePanel(robot: RemoteRobot): ComponentFixture {
        val panelXpaths = arrayOf(
            "//div[@class='dev.omnicode.ui.web.OmniCodeWebViewPanel']",
            "//div[contains(@class,'OmniCodeWebViewPanel')]",
        )
        panelXpaths.forEach { xpath ->
            runCatching { return robot.find<ComponentFixture>(byXpath(xpath), Duration.ofSeconds(2)) }
        }
        findAny(robot, Duration.ofSeconds(30),
            "//div[@accessiblename='OmniCode']",
            "//div[@tooltiptext='OmniCode']",
            "//div[@text='OmniCode']",
        ).click()
        return findAny(robot, Duration.ofSeconds(30), *panelXpaths)
    }

    private fun findAny(robot: RemoteRobot, timeout: Duration, vararg xpaths: String): ComponentFixture {
        require(xpaths.isNotEmpty())
        val perLocator = Duration.ofMillis((timeout.toMillis() / xpaths.size).coerceAtLeast(1_000))
        var lastFailure: Throwable? = null
        xpaths.forEach { xpath ->
            runCatching { robot.find<ComponentFixture>(byXpath(xpath), perLocator) }
                .onSuccess { return it }
                .onFailure { lastFailure = it }
        }
        throw AssertionError("None of the expected IDE controls appeared: ${xpaths.joinToString()}", lastFailure)
    }
}
