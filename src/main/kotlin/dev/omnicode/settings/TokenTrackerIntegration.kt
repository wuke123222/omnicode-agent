package dev.omnicode.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.util.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private const val TOKEN_TRACKER_LOOPBACK_ORIGIN = "http://127.0.0.1:7680"
internal const val TOKEN_TRACKER_DASHBOARD_URL = "$TOKEN_TRACKER_LOOPBACK_ORIGIN/dashboard"
internal const val TOKEN_TRACKER_DOCUMENTATION_URL = "https://github.com/xiufengsun/TokenTracker"
/**
 * TokenTracker's supported zero-setup entry point. Keeping this as an argv-like command string
 * means the plugin can copy it without running npm/npx or touching the user's shell.
 */
internal const val TOKEN_TRACKER_INSTALL_COMMAND = "npx tokentracker-cli"

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
    val usage: TokenTrackerUsageSnapshot? = null,
    val usageError: String? = null,
)

internal data class TokenTrackerUsageSnapshot(
    val from: String,
    val to: String,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val reasoningTokens: Long,
    val conversations: Long,
    val costUsd: Double,
    val activeDays: Int,
    val averagePerActiveDay: Long,
    val daily: List<TokenTrackerDailyUsage>,
    val models: List<TokenTrackerModelUsage>,
    val projects: List<TokenTrackerProjectUsage>,
)

internal data class TokenTrackerDailyUsage(
    val day: String,
    val tokens: Long,
    val costUsd: Double,
)

internal data class TokenTrackerModelUsage(
    val source: String,
    val model: String,
    val tokens: Long,
    val costUsd: Double,
)

internal data class TokenTrackerProjectUsage(
    val name: String,
    val tokens: Long,
)

/**
 * Read-only discovery for the optional TokenTracker companion.
 *
 * This class never starts the CLI, installs packages, reads TokenTracker's database directly, or
 * sends OmniCode usage records anywhere. It reads only TokenTracker's aggregate, read-only local
 * API through a bounded fixed-loopback connection. Local authentication values are never requested,
 * logged, persisted, or returned to the WebView.
 */
internal class TokenTrackerIntegration(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = defaultUserHome(),
    private val osName: String = System.getProperty("os.name", ""),
    private val probeDashboard: () -> TokenTrackerDashboardProbe = ::probeTokenTrackerDashboard,
    private val loadUsage: () -> TokenTrackerUsageSnapshot = ::loadTokenTrackerUsage,
) {
    fun inspect(): TokenTrackerStatus {
        val dashboard = runCatching(probeDashboard).getOrElse { error ->
            TokenTrackerDashboardProbe(
                TokenTrackerDashboardState.ERROR,
                safeTokenTrackerError(error),
            )
        }
        val usageResult = if (dashboard.state == TokenTrackerDashboardState.READY) {
            runCatching(loadUsage)
        } else {
            null
        }
        return TokenTrackerStatus(
            cliExecutable = findTokenTrackerExecutable(environment, userHome, osName),
            dashboard = dashboard,
            usage = usageResult?.getOrNull(),
            usageError = usageResult?.exceptionOrNull()?.let(::safeTokenTrackerError),
        )
    }
}

internal fun tokenTrackerStartCommand(osName: String = System.getProperty("os.name", "")): String =
    if (osName.lowercase(Locale.ROOT).contains("win")) {
        "\$env:TOKENTRACKER_NO_TELEMETRY='1'; npx tokentracker-cli"
    } else {
        "TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli"
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
    val uri = URI.create("$TOKEN_TRACKER_LOOPBACK_ORIGIN/")
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

private fun loadTokenTrackerUsage(
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    fetch: (String) -> String = ::fetchTokenTrackerJson,
): TokenTrackerUsageSnapshot {
    val from = today.minusDays(TOKEN_TRACKER_RANGE_DAYS - 1L).toString()
    val to = today.toString()
    val query = "from=$from&to=$to&timeZone=${urlEncode(zoneId.id)}"
    // The headline is the only required response. Optional breakdowns degrade independently so a
    // slow project attribution scan cannot blank otherwise healthy usage totals and trends.
    val summary = fetch("/functions/tokentracker-usage-summary?$query")
    val daily = runCatching { fetch("/functions/tokentracker-usage-daily?$query") }
        .getOrDefault("{\"data\":[]}")
    val models = runCatching { fetch("/functions/tokentracker-usage-model-breakdown?$query") }
        .getOrDefault("{\"sources\":[]}")
    val projects = runCatching {
        fetch("/functions/tokentracker-project-usage-summary?$query&limit=$TOKEN_TRACKER_MAX_PROJECTS")
    }.getOrDefault("{\"entries\":[]}")
    return parseTokenTrackerUsage(
        summaryJson = summary,
        dailyJson = daily,
        modelsJson = models,
        projectsJson = projects,
        fallbackFrom = from,
        fallbackTo = to,
    )
}

internal fun parseTokenTrackerUsage(
    summaryJson: String,
    dailyJson: String,
    modelsJson: String,
    projectsJson: String,
    fallbackFrom: String,
    fallbackTo: String,
): TokenTrackerUsageSnapshot {
    val summary = Json.parseObject(summaryJson)
    val totals = summary.objectOrEmpty("totals")
    val rolling30 = summary.objectOrEmpty("rolling").objectOrEmpty("last_30d")
    val daily = Json.parseObject(dailyJson).arrayOrEmpty("data")
        .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
        .takeLast(TOKEN_TRACKER_RANGE_DAYS)
        .map { row ->
            TokenTrackerDailyUsage(
                day = row.safeString("day", 10),
                tokens = row.safeLong("billable_total_tokens"),
                costUsd = row.safeDouble("total_cost_usd"),
            )
        }
        .filter { it.day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }

    val models = Json.parseObject(modelsJson).arrayOrEmpty("sources")
        .flatMap { sourceElement ->
            val source = sourceElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
            val sourceName = source.safeString("source", TOKEN_TRACKER_MAX_LABEL_CHARS).ifBlank { "unknown" }
            source.arrayOrEmpty("models").mapNotNull modelLoop@ { modelElement ->
                val model = modelElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@modelLoop null
                val modelTotals = model.objectOrEmpty("totals")
                TokenTrackerModelUsage(
                    source = sourceName,
                    model = model.safeString("model", TOKEN_TRACKER_MAX_LABEL_CHARS).ifBlank { "unknown" },
                    tokens = modelTotals.safeLong("billable_total_tokens"),
                    costUsd = modelTotals.safeDouble("total_cost_usd"),
                )
            }
        }
        .sortedByDescending(TokenTrackerModelUsage::tokens)
        .take(TOKEN_TRACKER_MAX_MODELS)

    val projects = Json.parseObject(projectsJson).arrayOrEmpty("entries")
        .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
        .map { row ->
            val key = row.safeString("project_key", TOKEN_TRACKER_MAX_PROJECT_KEY_CHARS)
            TokenTrackerProjectUsage(
                name = projectDisplayName(key),
                tokens = row.safeLong("billable_total_tokens"),
            )
        }
        .filter { it.name.isNotBlank() }
        .sortedByDescending(TokenTrackerProjectUsage::tokens)
        .take(TOKEN_TRACKER_MAX_PROJECTS)

    return TokenTrackerUsageSnapshot(
        from = summary.safeString("from", 10).ifBlank { fallbackFrom },
        to = summary.safeString("to", 10).ifBlank { fallbackTo },
        totalTokens = totals.safeLong("billable_total_tokens"),
        inputTokens = totals.safeLong("input_tokens"),
        outputTokens = totals.safeLong("output_tokens"),
        cachedTokens = totals.safeLong("cached_input_tokens"),
        reasoningTokens = totals.safeLong("reasoning_output_tokens"),
        conversations = totals.safeLong("conversation_count"),
        costUsd = totals.safeDouble("total_cost_usd"),
        activeDays = rolling30.safeInt("active_days").coerceAtLeast(daily.count { it.tokens > 0 }),
        averagePerActiveDay = rolling30.safeLong("avg_per_active_day"),
        daily = daily,
        models = models,
        projects = projects,
    )
}

private fun fetchTokenTrackerJson(pathAndQuery: String): String {
    require(pathAndQuery.startsWith("/") && !pathAndQuery.startsWith("//")) {
        "TokenTracker API path must be relative to the fixed loopback endpoint"
    }
    val uri = URI.create("$TOKEN_TRACKER_LOOPBACK_ORIGIN$pathAndQuery")
    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 7680) {
        "TokenTracker API must remain on the fixed loopback endpoint"
    }
    val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TOKEN_TRACKER_DATA_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = TOKEN_TRACKER_DATA_READ_TIMEOUT_MILLIS
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OmniCode-TokenTracker-ReadOnly")
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) throw IOException("TokenTracker 本机接口返回 HTTP $statusCode")
        val bytes = connection.inputStream.use { input ->
            val bounded = input.readNBytes(TOKEN_TRACKER_MAX_DATA_BYTES + 1)
            if (bounded.size > TOKEN_TRACKER_MAX_DATA_BYTES) throw IOException("TokenTracker 返回数据超过安全上限")
            bounded
        }
        String(bytes, StandardCharsets.UTF_8)
    } finally {
        connection.disconnect()
    }
}

private fun JsonObject.objectOrEmpty(name: String): JsonObject =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

private fun JsonObject.safeString(name: String, maxChars: Int): String =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }
        .getOrDefault("")
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
        .take(maxChars)

private fun JsonObject.safeLong(name: String): Long =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong ?: 0L }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

private fun JsonObject.safeInt(name: String): Int = safeLong(name).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun JsonObject.safeDouble(name: String): Double =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0 }
        .getOrDefault(0.0)
        .takeIf(Double::isFinite)
        ?.coerceAtLeast(0.0)
        ?: 0.0

private fun projectDisplayName(projectKey: String): String =
    projectKey.replace('\\', '/').trimEnd('/').substringAfterLast('/').take(TOKEN_TRACKER_MAX_LABEL_CHARS)

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

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
private const val TOKEN_TRACKER_DATA_CONNECT_TIMEOUT_MILLIS = 1_000
private const val TOKEN_TRACKER_DATA_READ_TIMEOUT_MILLIS = 3_000
private const val TOKEN_TRACKER_MAX_PROBE_BYTES = 32 * 1024
private const val TOKEN_TRACKER_MAX_DATA_BYTES = 1024 * 1024
private const val TOKEN_TRACKER_MAX_ERROR_CHARS = 200
private const val TOKEN_TRACKER_RANGE_DAYS = 30
private const val TOKEN_TRACKER_MAX_MODELS = 10
private const val TOKEN_TRACKER_MAX_PROJECTS = 8
private const val TOKEN_TRACKER_MAX_LABEL_CHARS = 120
private const val TOKEN_TRACKER_MAX_PROJECT_KEY_CHARS = 512
