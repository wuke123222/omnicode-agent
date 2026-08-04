package dev.omnicode.settings

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal const val TOKEN_TRACKER_DASHBOARD_URL = "http://127.0.0.1:7680/"
internal const val TOKEN_TRACKER_DOCUMENTATION_URL = "https://github.com/xiufengsun/TokenTracker"
internal const val TOKEN_TRACKER_INSTALL_COMMAND = "npm install --global tokentracker-cli"

internal enum class TokenTrackerDashboardState {
    READY,
    NOT_RUNNING,
    UNVERIFIED_SERVICE,
    ERROR,
}

internal data class TokenTrackerDashboardProbe(
    val state: TokenTrackerDashboardState,
    val detail: String,
)

internal data class TokenTrackerStatus(
    val cliExecutable: Path?,
    val dashboard: TokenTrackerDashboardProbe,
)

/**
 * Read-only discovery for the optional TokenTracker companion.
 *
 * This class never starts the CLI, installs packages, reads TokenTracker's database, or sends
 * OmniCode usage records anywhere. The only network operation is a bounded request to the fixed
 * IPv4 loopback dashboard address.
 */
internal class TokenTrackerIntegration(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = defaultUserHome(),
    private val osName: String = System.getProperty("os.name", ""),
    private val probeDashboard: () -> TokenTrackerDashboardProbe = ::probeTokenTrackerDashboard,
) {
    fun inspect(): TokenTrackerStatus = TokenTrackerStatus(
        cliExecutable = findTokenTrackerExecutable(environment, userHome, osName),
        dashboard = runCatching(probeDashboard).getOrElse { error ->
            TokenTrackerDashboardProbe(
                TokenTrackerDashboardState.ERROR,
                safeTokenTrackerError(error),
            )
        },
    )
}

internal fun tokenTrackerStartCommand(osName: String = System.getProperty("os.name", "")): String =
    if (osName.lowercase(Locale.ROOT).contains("win")) {
        "\$env:TOKENTRACKER_NO_TELEMETRY='1'; tokentracker"
    } else {
        "TOKENTRACKER_NO_TELEMETRY=1 tokentracker"
    }

internal fun findTokenTrackerExecutable(
    environment: Map<String, String>,
    userHome: Path,
    osName: String,
): Path? {
    val windows = osName.lowercase(Locale.ROOT).contains("win")
    val names = if (windows) {
        listOf("tokentracker.exe", "tokentracker.cmd", "tokentracker.bat")
    } else {
        listOf("tokentracker")
    }
    val directories = linkedSetOf<Path>()
    environment.entries
        .firstOrNull { (key, _) -> key.equals("PATH", ignoreCase = windows) }
        ?.value
        ?.split(File.pathSeparatorChar)
        .orEmpty()
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { value -> value.removeSurrounding("\"") }
        .mapNotNull { value -> runCatching { Path.of(value) }.getOrNull() }
        // A relative PATH entry could resolve to a repository-controlled executable. Discovery is
        // intentionally limited to absolute host locations.
        .filter(Path::isAbsolute)
        .map { path -> path.normalize() }
        .forEach(directories::add)

    val normalizedHome = userHome.toAbsolutePath().normalize()
    listOf(
        normalizedHome.resolve(".local/bin"),
        normalizedHome.resolve(".npm/bin"),
        normalizedHome.resolve(".npm-global/bin"),
        normalizedHome.resolve("Library/pnpm"),
        Path.of("/opt/homebrew/bin"),
        Path.of("/usr/local/bin"),
        Path.of("/usr/bin"),
    ).forEach(directories::add)

    return directories.asSequence()
        .flatMap { directory -> names.asSequence().map(directory::resolve) }
        .map { candidate -> candidate.normalize() }
        .firstOrNull { candidate ->
            Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))
        }
}

internal fun classifyTokenTrackerDashboard(statusCode: Int, body: String): TokenTrackerDashboardProbe {
    if (statusCode !in 200..299) {
        return TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.UNVERIFIED_SERVICE,
            "本地端口返回 HTTP $statusCode，未识别为 TokenTracker。",
        )
    }
    val normalized = body.lowercase(Locale.ROOT)
    val recognized = "tokentracker" in normalized || "token tracker" in normalized
    return if (recognized) {
        TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.READY,
            "本地面板已就绪。",
        )
    } else {
        TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.UNVERIFIED_SERVICE,
            "端口 7680 正在响应，但页面未识别为 TokenTracker。",
        )
    }
}

private fun probeTokenTrackerDashboard(): TokenTrackerDashboardProbe {
    val uri = URI.create(TOKEN_TRACKER_DASHBOARD_URL)
    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 7680) {
        "TokenTracker dashboard probe must remain on the fixed loopback endpoint"
    }
    val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TOKEN_TRACKER_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = TOKEN_TRACKER_READ_TIMEOUT_MILLIS
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "OmniCode-TokenTracker-Local-Probe")
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            val bytes = input.readNBytes(TOKEN_TRACKER_MAX_PROBE_BYTES)
            String(bytes, StandardCharsets.UTF_8)
        }.orEmpty()
        classifyTokenTrackerDashboard(statusCode, body)
    } catch (_: java.net.ConnectException) {
        TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.NOT_RUNNING,
            "本地面板未运行。",
        )
    } catch (_: SocketTimeoutException) {
        TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.NOT_RUNNING,
            "本地面板连接超时。",
        )
    } catch (error: IOException) {
        TokenTrackerDashboardProbe(
            TokenTrackerDashboardState.ERROR,
            safeTokenTrackerError(error),
        )
    } finally {
        connection.disconnect()
    }
}

private fun safeTokenTrackerError(error: Throwable): String =
    error.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.take(TOKEN_TRACKER_MAX_ERROR_CHARS)
        ?.ifBlank { null }
        ?: error::class.java.simpleName

private fun defaultUserHome(): Path =
    Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize()

private const val TOKEN_TRACKER_CONNECT_TIMEOUT_MILLIS = 700
private const val TOKEN_TRACKER_READ_TIMEOUT_MILLIS = 1_000
private const val TOKEN_TRACKER_MAX_PROBE_BYTES = 32 * 1024
private const val TOKEN_TRACKER_MAX_ERROR_CHARS = 200
