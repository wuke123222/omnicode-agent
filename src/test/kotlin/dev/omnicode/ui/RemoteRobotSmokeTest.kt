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
        persistScreenshot(screenshot)
        dismissFirstRunTour(robot)
        // If the onboarding control changes and is not recognized, retain evidence of the
        // unobstructed state (or of the remaining overlay) before panel discovery starts.
        persistScreenshot(robot.getScreenshot())
        val panel = findReadyOmniCodePanel(robot)
        assertTrue(panel.isShowing, "OmniCode Tool Window did not become visible")
        val panelScreenshot = panel.getScreenshot()
        require(panelScreenshot.width >= 300) { "OmniCode Tool Window is unexpectedly narrow" }
        persistScreenshot(panelScreenshot, "OMNICODE_REMOTE_ROBOT_PANEL_SCREENSHOT")

        // Persist the real desktop frame when the CI job requests it. The workflow uploads this
        // artifact for human review; local/headless unit runs never write an implicit file.
        persistScreenshot(robot.getScreenshot())
        // Exercise the HTTP client again with a bounded native query, proving that the test is
        // talking to Robot Server rather than merely constructing a client object.
        require(robot.os.isNotBlank())
    }

    private fun persistScreenshot(
        screenshot: java.awt.image.BufferedImage,
        variable: String = "OMNICODE_REMOTE_ROBOT_SCREENSHOT",
    ) {
        System.getenv(variable)?.trim()?.takeIf(String::isNotBlank)?.let { path ->
            val target = File(path).canonicalFile
            target.parentFile?.mkdirs()
            check(ImageIO.write(screenshot, "png", target)) { "PNG screenshot writer is unavailable" }
            require(target.length() in 1..8_000_000) { "Remote Robot screenshot exceeded the evidence bound" }
        }
    }

    private fun findOmniCodePanel(robot: RemoteRobot): ComponentFixture {
        val panelXpaths = arrayOf(
            "//div[contains(@accessiblename,'OmniCode WebView')]",
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

    private fun findReadyOmniCodePanel(robot: RemoteRobot): ComponentFixture {
        findOmniCodePanel(robot)
        return findAny(
            robot,
            Duration.ofSeconds(75),
            "//div[@accessiblename='OmniCode WebView Ready']",
        )
    }

    /**
     * Fresh IDE profiles can show the first-run "Meet the Islands Theme" tour above the tool
     * window. It is unrelated to OmniCode, but blocks all underlying controls in the desktop
     * smoke test. Dismiss it when present; normal/already-initialized profiles are unchanged.
     */
    private fun dismissFirstRunTour(robot: RemoteRobot) {
        val skipXpaths = arrayOf(
            "//div[@accessiblename='Skip']",
            "//div[@text='Skip']",
            "//div[@tooltiptext='Skip']",
        )
        skipXpaths.forEach { xpath ->
            runCatching {
                robot.find<ComponentFixture>(byXpath(xpath), Duration.ofSeconds(2)).click()
                return
            }
        }
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
