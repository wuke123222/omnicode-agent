package dev.omnicode.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import dev.omnicode.mcp.oauth.oauthConfigurationBinding
import dev.omnicode.mcp.validateMcpHttpEndpoint
import dev.omnicode.provider.ProviderConnection
import dev.omnicode.provider.ProviderPreset
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.provider.likelySupportsVision
import dev.omnicode.provider.modelApiBaseUrlValidationError
import dev.omnicode.settings.McpEnvironmentCredentialStore
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpHttpCredentialStore
import dev.omnicode.settings.McpOAuthCredentialStore
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import dev.omnicode.settings.OAUTH_SCOPE_TOKEN
import dev.omnicode.settings.OmniCodeCredentialStore
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.settings.ProviderSecrets
import dev.omnicode.settings.SandboxMode
import dev.omnicode.settings.isValidMcpEnvironmentKey
import dev.omnicode.settings.resolveProviderSecrets
import dev.omnicode.tool.ProcessSandbox
import dev.omnicode.tool.SandboxCapability
import dev.omnicode.tool.SandboxEnforcement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.time.Clock
import java.util.Locale

@Service(Service.Level.APP)
class ConnectionDiagnosticsService internal constructor(
    private val networkProbe: ConnectionDiagnosticsNetworkProbe,
    private val timeouts: ConnectionDiagnosticsTimeouts,
    private val propertyReader: (String) -> String?,
    private val environmentReader: (String) -> String?,
    private val sandboxCapability: (SandboxMode) -> SandboxCapability,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) {
    constructor() : this(
        networkProbe = JdkConnectionDiagnosticsNetworkProbe(),
        timeouts = ConnectionDiagnosticsTimeouts(),
        propertyReader = System::getProperty,
        environmentReader = System::getenv,
        sandboxCapability = defaultSandboxCapabilityReader(),
        clock = Clock.systemUTC(),
        nanoTime = System::nanoTime,
    )

    /**
     * Reads only configured settings and secret-presence bits, then runs the bounded probe.
     * Passwords/tokens are discarded before [ConnectionDiagnosticsInput] is constructed.
     */
    suspend fun diagnoseCurrentConfiguration(): ConnectionDiagnosticsReport {
        val input = withContext(Dispatchers.IO) { currentInput() }
        return diagnose(input)
    }

    /** Runs deterministic local checks plus the injected credential-free provider probe. */
    suspend fun diagnose(input: ConnectionDiagnosticsInput): ConnectionDiagnosticsReport {
        val reportStarted = nanoTime()
        val generatedAt = clock.instant()
        val checks = mutableListOf<ConnectionDiagnosticCheck>()
        val preset = ProviderPresets.all.firstOrNull { it.id == input.provider.providerId }

        checks += measuredCheck(
            id = "provider.configuration",
            category = ConnectionDiagnosticCategory.PROVIDER,
            title = "Provider configuration",
        ) {
            providerConfigurationOutcome(input, preset)
        }
        checks += measuredCheck(
            id = "provider.credentials",
            category = ConnectionDiagnosticCategory.PROVIDER,
            title = "Provider credentials",
        ) {
            providerCredentialOutcome(input, preset)
        }

        val endpoint = if (preset?.protocol == ProviderProtocol.CODEX_APP_SERVER) {
            null
        } else {
            providerEndpoint(input.provider.baseUrl, input.provider.region)
        }
        checks += measuredCheck(
            id = "provider.base_url",
            category = ConnectionDiagnosticCategory.PROVIDER,
            title = "Provider Base URL",
        ) {
            if (preset?.protocol == ProviderProtocol.CODEX_APP_SERVER) {
                outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "Codex 原生使用本机 App Server；不需要 HTTP Base URL 探测。",
                )
            } else if (endpoint == null) {
                outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "The configured Base URL is invalid or unsafe.",
                    "Use HTTPS for remote providers, remove credentials, query parameters and fragments, then save again.",
                )
            } else {
                outcome(ConnectionDiagnosticStatus.PASS, "The configured Base URL has a valid, credential-free origin.")
            }
        }
        checks += measuredCheck(
            id = "network.proxy",
            category = ConnectionDiagnosticCategory.NETWORK,
            title = "JVM proxy settings",
        ) {
            proxyOutcome()
        }

        checks += measuredCheck(
            id = "network.dns",
            category = ConnectionDiagnosticCategory.NETWORK,
            title = "Provider DNS",
        ) {
            if (preset?.protocol == ProviderProtocol.CODEX_APP_SERVER) {
                outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "Codex 原生连接不经过供应商 DNS。",
                )
            } else if (endpoint == null) {
                outcome(
                    ConnectionDiagnosticStatus.SKIP,
                    "DNS was not attempted because the Base URL is invalid.",
                    "Fix the Provider Base URL first.",
                )
            } else {
                resolveDns(endpoint.host)
            }
        }
        checks += measuredCheck(
            id = "network.tls_http",
            category = ConnectionDiagnosticCategory.NETWORK,
            title = "Provider TLS / HTTP",
        ) {
            when {
                preset?.protocol == ProviderProtocol.CODEX_APP_SERVER -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "Codex 原生 App Server 将在任务启动时由本机 Codex 可执行文件提供。",
                )
                endpoint == null -> outcome(
                    ConnectionDiagnosticStatus.SKIP,
                    "TLS/HTTP was not attempted because the Base URL is invalid.",
                    "Fix the Provider Base URL first.",
                )
                else -> probeEndpoint(endpoint)
            }
        }

        checks += measuredCheck(
            id = "model.tools",
            category = ConnectionDiagnosticCategory.MODEL,
            title = "Model tool calling",
        ) { modelToolOutcome(input, preset) }
        checks += measuredCheck(
            id = "model.vision",
            category = ConnectionDiagnosticCategory.MODEL,
            title = "Primary model vision",
        ) { primaryVisionOutcome(input, preset) }
        checks += measuredCheck(
            id = "model.vision_assistant",
            category = ConnectionDiagnosticCategory.MODEL,
            title = "Vision assistant",
        ) { visionAssistantOutcome(input, preset) }

        addMcpChecks(input, checks)
        addSandboxChecks(input.sandboxMode, checks)

        return ConnectionDiagnosticsReport(
            generatedAt = generatedAt,
            durationMillis = elapsedMillis(reportStarted),
            checks = checks,
        )
    }

    private fun currentInput(): ConnectionDiagnosticsInput {
        val settings = OmniCodeSettingsService.getInstance()
        val provider = settings.snapshot()
        val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
        return ConnectionDiagnosticsInput(
            provider = provider,
            providerCredentials = inspectProviderCredentials(provider.providerId, provider.baseUrl),
            visionAssistantModel = settings.visionModelFor(provider.providerId),
            mcpServers = platform.mcpServers,
            mcpCredentialsByServerId = platform.mcpServers.take(MAX_DIAGNOSTIC_MCP_SERVERS).associate { config ->
                config.id to inspectMcpCredentials(config)
            },
            sandboxMode = platform.sandboxMode,
        )
    }

    private fun inspectProviderCredentials(
        providerId: String,
        baseUrl: String,
    ): ConnectionDiagnosticsProviderCredentials {
        val stored = runCatching { OmniCodeCredentialStore.getInstance().load(providerId, baseUrl) }
            .getOrElse {
                return ConnectionDiagnosticsProviderCredentials(inspectionFailed = true)
            }
        val resolved = runCatching {
            resolveProviderSecrets(providerId, stored, baseUrl, environmentReader)
        }.getOrElse {
            return credentialPresence(stored).copy(inspectionFailed = true)
        }
        val awsPair = runCatching {
            environmentReader("AWS_ACCESS_KEY_ID")?.isNotBlank() == true &&
                environmentReader("AWS_SECRET_ACCESS_KEY")?.isNotBlank() == true
        }.getOrDefault(false)
        return credentialPresence(resolved.secrets).copy(
            environmentApiKeyConfigured = resolved.environmentVariable != null,
            environmentCredentialBlocked = resolved.blockedEnvironmentVariable != null,
            awsCredentialPairConfigured = awsPair,
        )
    }

    private fun credentialPresence(secrets: ProviderSecrets) = ConnectionDiagnosticsProviderCredentials(
        apiKeyConfigured = secrets.apiKey.isNotBlank(),
        secondarySecretConfigured = secrets.secondarySecret.isNotBlank(),
        sessionTokenConfigured = secrets.sessionToken.isNotBlank(),
    )

    private fun inspectMcpCredentials(config: McpServerConfig): ConnectionDiagnosticsMcpCredentials {
        var inspectionAvailable = true
        val bearer = if (config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.BEARER) {
            runCatching { McpHttpCredentialStore.getInstance().hasToken(config.id) }
                .getOrElse {
                    inspectionAvailable = false
                    false
                }
        } else {
            false
        }
        val environmentSecretCount = config.environmentKeys.count { key ->
            runCatching { McpEnvironmentCredentialStore.getInstance().hasSecret(config.id, key) }
                .getOrElse {
                    inspectionAvailable = false
                    false
                }
        }
        val oauth = if (config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.OAUTH) {
            runCatching {
                val session = McpOAuthCredentialStore.getInstance().load(config.id)
                    ?: return@runCatching ConnectionDiagnosticsOAuthState()
                ConnectionDiagnosticsOAuthState(
                    sessionPresent = true,
                    configurationMatches = runCatching {
                        session.configurationBinding == oauthConfigurationBinding(config)
                    }.getOrDefault(false),
                    resourceMetadataPresent = session.resource.isNotBlank(),
                    issuerMetadataPresent = session.issuer.isNotBlank(),
                    tokenEndpointMetadataPresent = session.tokenEndpoint.isNotBlank(),
                    clientIdPresent = session.clientId.isNotBlank(),
                    accessTokenPresent = session.accessToken.isNotBlank(),
                    refreshTokenPresent = session.refreshToken.isNotBlank(),
                    accessTokenExpired = session.expiresAtEpochMillis > 0L &&
                        session.expiresAtEpochMillis <= clock.millis(),
                )
            }.getOrElse {
                inspectionAvailable = false
                ConnectionDiagnosticsOAuthState()
            }
        } else {
            ConnectionDiagnosticsOAuthState()
        }
        return ConnectionDiagnosticsMcpCredentials(
            inspectionAvailable = inspectionAvailable,
            bearerTokenConfigured = bearer,
            configuredEnvironmentSecretCount = environmentSecretCount,
            oauth = oauth,
        )
    }

    private fun providerConfigurationOutcome(
        input: ConnectionDiagnosticsInput,
        preset: ProviderPreset?,
    ): CheckOutcome = when {
        preset == null -> outcome(
            ConnectionDiagnosticStatus.FAIL,
            "The selected provider is not recognized by this OmniCode build.",
            "Select a provider from Settings and save the profile again.",
        )
        input.provider.model.isBlank() -> outcome(
            ConnectionDiagnosticStatus.FAIL,
            "No model is configured for the selected provider.",
            "Enter a model or deployment ID in Provider settings.",
        )
        preset.protocol == ProviderProtocol.BEDROCK_CONVERSE && input.provider.region.isBlank() -> outcome(
            ConnectionDiagnosticStatus.FAIL,
            "AWS Region is missing for the Bedrock provider.",
            "Configure a Bedrock Region and save the provider profile.",
        )
        preset.protocol == ProviderProtocol.AZURE_OPENAI &&
            (input.provider.baseUrl.contains("YOUR-RESOURCE") || input.provider.model.contains("YOUR-DEPLOYMENT")) -> outcome(
            ConnectionDiagnosticStatus.FAIL,
            "The Azure provider still contains placeholder values.",
            "Replace the resource URL and deployment name with values from Azure OpenAI.",
        )
        else -> outcome(ConnectionDiagnosticStatus.PASS, "Provider and model selections are present.")
    }

    private fun providerCredentialOutcome(
        input: ConnectionDiagnosticsInput,
        preset: ProviderPreset?,
    ): CheckOutcome {
        val credentials = input.providerCredentials
        if (preset == null) {
            return outcome(ConnectionDiagnosticStatus.SKIP, "Credentials cannot be evaluated for an unknown provider.")
        }
        if (credentials.inspectionFailed) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "Credential presence could not be read from Password Safe.",
                "Unlock or repair the IDE Password Safe, then run diagnostics again.",
            )
        }
        if (credentials.environmentCredentialBlocked) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "An environment credential was intentionally blocked for this custom remote origin.",
                "Explicitly save a credential for this Base URL after verifying the destination.",
            )
        }
        if (preset.protocol == ProviderProtocol.BEDROCK_CONVERSE) {
            val configured = credentials.apiKeyConfigured || credentials.awsCredentialPairConfigured
            return if (configured) {
                outcome(ConnectionDiagnosticStatus.PASS, "Bedrock credential presence was confirmed without reading it into the report.")
            } else {
                outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "No Bedrock API key or complete AWS credential pair is available.",
                    "Configure a Bedrock API key, an access/secret key pair, or the standard AWS credential environment.",
                )
            }
        }
        val configured = credentials.apiKeyConfigured || credentials.environmentApiKeyConfigured
        return when {
            configured -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "Provider credential presence was confirmed; its value was not copied into diagnostics.",
            )
            preset.apiKeyOptional -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "This provider allows a connection without an API key.",
            )
            else -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The selected provider requires an API key, but none is configured.",
                "Save the provider API key in the IDE Password Safe, then retry.",
            )
        }
    }

    private fun proxyOutcome(): CheckOutcome {
        val httpsHost = propertyReader("https.proxyHost")?.trim().orEmpty()
        val httpHost = propertyReader("http.proxyHost")?.trim().orEmpty()
        val configured = httpsHost.isNotEmpty() || httpHost.isNotEmpty()
        val invalidHost = sequenceOf(httpsHost, httpHost)
            .filter(String::isNotEmpty)
            .any { host -> host.any(Char::isISOControl) || host.any { it in "/@" } || "://" in host }
        val invalidPort = listOf(
            httpsHost to propertyReader("https.proxyPort"),
            httpHost to propertyReader("http.proxyPort"),
        ).any { (host, port) -> host.isNotEmpty() && !port.isNullOrBlank() && port.toIntOrNull() !in 1..65_535 }
        val credentialPropertiesPresent = PROXY_CREDENTIAL_PROPERTIES.any { propertyReader(it)?.isNotBlank() == true }
        val systemProxyValue = propertyReader("java.net.useSystemProxies")?.trim()
        val invalidSystemProxyFlag = !systemProxyValue.isNullOrEmpty() &&
            !systemProxyValue.equals("true", true) && !systemProxyValue.equals("false", true)
        return when {
            invalidHost || invalidPort || invalidSystemProxyFlag -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "One or more JVM proxy properties are malformed.",
                "Correct the proxy port/boolean properties or remove them and use the IDE proxy configuration.",
            )
            credentialPropertiesPresent -> outcome(
                ConnectionDiagnosticStatus.WARN,
                "A proxy is configured with credential-bearing JVM properties; values are excluded from diagnostics.",
                "Prefer the IDE proxy credential store and avoid plaintext JVM proxy password properties.",
            )
            configured || systemProxyValue.equals("true", true) -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "System proxy configuration is present and structurally valid; values are excluded from diagnostics.",
            )
            else -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "No JVM proxy properties are configured; direct or IDE-managed networking will be used.",
            )
        }
    }

    private suspend fun resolveDns(host: String): CheckOutcome {
        val result = try {
            withTimeout(timeouts.dnsMillis) { networkProbe.resolve(host, timeouts.dnsMillis) }
        } catch (_: TimeoutCancellationException) {
            ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.OTHER)
        }
        return when (result.failure) {
            null -> outcome(ConnectionDiagnosticStatus.PASS, "The provider hostname resolved successfully.")
            ConnectionDiagnosticsNetworkFailure.TIMEOUT -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "Provider DNS resolution exceeded the bounded timeout.",
                "Check DNS, VPN and proxy settings, then retry.",
            )
            ConnectionDiagnosticsNetworkFailure.DNS_NOT_FOUND -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The provider hostname did not resolve.",
                "Verify the Base URL and local DNS/VPN configuration.",
            )
            else -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "Provider DNS resolution failed without exposing the system error text.",
                "Check DNS, VPN and proxy settings, then retry.",
            )
        }
    }

    private suspend fun probeEndpoint(endpoint: URI): CheckOutcome {
        val result = try {
            withTimeout(timeouts.requestMillis) {
                networkProbe.probe(endpoint, timeouts.connectMillis, timeouts.requestMillis)
            }
        } catch (_: TimeoutCancellationException) {
            ConnectionDiagnosticsEndpointResult(failure = ConnectionDiagnosticsNetworkFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ConnectionDiagnosticsEndpointResult(failure = ConnectionDiagnosticsNetworkFailure.OTHER)
        }
        result.failure?.let { failure ->
            val summary = when (failure) {
                ConnectionDiagnosticsNetworkFailure.TIMEOUT -> "The provider endpoint exceeded the bounded connection timeout."
                ConnectionDiagnosticsNetworkFailure.TLS -> "TLS negotiation with the provider endpoint failed."
                ConnectionDiagnosticsNetworkFailure.DNS_NOT_FOUND -> "The provider hostname stopped resolving during the endpoint probe."
                ConnectionDiagnosticsNetworkFailure.CONNECTION -> "A network connection to the provider endpoint could not be established."
                ConnectionDiagnosticsNetworkFailure.PROTOCOL -> "The provider endpoint returned an invalid HTTP protocol response."
                ConnectionDiagnosticsNetworkFailure.OTHER -> "The provider endpoint probe failed without exposing system error text."
            }
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                summary,
                "Check proxy, DNS, firewall and TLS interception settings, then retry.",
            )
        }
        if (endpoint.scheme.equals("https", true) && !result.tlsEstablished) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "HTTPS responded without a verifiable TLS session.",
                "Check TLS interception and the configured provider URL.",
            )
        }
        val status = checkNotNull(result.statusCode)
        return when {
            status == 407 -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The proxy is reachable but requires authentication.",
                "Configure proxy credentials in the IDE and retry.",
            )
            status in 300..399 -> outcome(
                ConnectionDiagnosticStatus.WARN,
                "The configured provider endpoint redirects; diagnostics intentionally did not follow it.",
                "Configure the final provider Base URL directly.",
            )
            status >= 500 -> outcome(
                ConnectionDiagnosticStatus.WARN,
                "TLS/HTTP connectivity succeeded, but the provider reported a server-side error.",
                "Retry later or check the provider status page.",
            )
            status == 401 || status == 403 -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "TLS/HTTP connectivity succeeded and the credential-free probe received the expected authentication response.",
            )
            else -> outcome(
                ConnectionDiagnosticStatus.PASS,
                if (endpoint.scheme.equals("https", true)) {
                    "TLS/HTTP connectivity to the exact configured provider endpoint succeeded."
                } else {
                    "HTTP connectivity to the allowed loopback provider endpoint succeeded; TLS is not applicable."
                },
            )
        }
    }

    private fun modelToolOutcome(input: ConnectionDiagnosticsInput, preset: ProviderPreset?): CheckOutcome {
        if (preset == null || input.provider.model.isBlank()) {
            return outcome(ConnectionDiagnosticStatus.SKIP, "Tool capability cannot be evaluated until provider and model are configured.")
        }
        return when (likelyToolCapability(input.provider.model)) {
            LocalCapability.YES -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "The local provider/model classification supports tool calling.",
            )
            LocalCapability.NO -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The configured model appears to be a specialized non-chat model and cannot drive Agent tools.",
                "Select a chat or coding model with function/tool calling support.",
            )
            LocalCapability.UNKNOWN -> outcome(
                ConnectionDiagnosticStatus.WARN,
                "The provider adapter supports tools, but this model ID is not in the local capability heuristics.",
                "Confirm tool calling support in the provider documentation or choose a known chat/coding model.",
            )
        }
    }

    private fun primaryVisionOutcome(input: ConnectionDiagnosticsInput, preset: ProviderPreset?): CheckOutcome {
        if (preset == null || input.provider.model.isBlank()) {
            return outcome(ConnectionDiagnosticStatus.SKIP, "Vision capability cannot be evaluated until provider and model are configured.")
        }
        return if (connectionFor(preset, input.provider.model, input).likelySupportsVision()) {
            outcome(ConnectionDiagnosticStatus.PASS, "The primary model is locally classified as vision-capable.")
        } else {
            outcome(
                ConnectionDiagnosticStatus.WARN,
                "The primary model is conservatively classified as text-only.",
                "Configure a vision assistant model if image attachments are required.",
            )
        }
    }

    private fun visionAssistantOutcome(input: ConnectionDiagnosticsInput, preset: ProviderPreset?): CheckOutcome {
        if (preset == null) {
            return outcome(ConnectionDiagnosticStatus.SKIP, "Vision assistant configuration cannot be evaluated for an unknown provider.")
        }
        val primarySupportsVision = input.provider.model.isNotBlank() &&
            connectionFor(preset, input.provider.model, input).likelySupportsVision()
        val assistant = input.visionAssistantModel.trim()
        return when {
            assistant.isEmpty() && primarySupportsVision -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "No vision assistant is needed because the primary model supports images.",
            )
            assistant.isEmpty() -> outcome(
                ConnectionDiagnosticStatus.WARN,
                "No vision assistant is configured for the text-only primary model.",
                "Set a vision-capable model in the provider profile before attaching images.",
            )
            connectionFor(preset, assistant, input).likelySupportsVision() -> outcome(
                ConnectionDiagnosticStatus.PASS,
                "The configured vision assistant is locally classified as vision-capable.",
            )
            else -> outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The configured vision assistant is not locally recognized as vision-capable.",
                "Choose a multimodal/vision model from the same provider.",
            )
        }
    }

    private fun connectionFor(
        preset: ProviderPreset,
        model: String,
        input: ConnectionDiagnosticsInput,
    ): ProviderConnection = ProviderConnection(
        preset = preset,
        baseUrl = input.provider.baseUrl,
        model = model,
        apiKey = "",
        region = input.provider.region,
        apiVersion = input.provider.apiVersion,
    )

    private suspend fun addMcpChecks(
        input: ConnectionDiagnosticsInput,
        checks: MutableList<ConnectionDiagnosticCheck>,
    ) {
        val inspectedServers = input.mcpServers.take(MAX_DIAGNOSTIC_MCP_SERVERS)
        checks += measuredCheck(
            id = "mcp.configuration",
            category = ConnectionDiagnosticCategory.MCP,
            title = "MCP configuration overview",
        ) {
            if (input.mcpServers.isEmpty()) {
                outcome(ConnectionDiagnosticStatus.SKIP, "No MCP servers are configured.")
            } else if (input.mcpServers.size > inspectedServers.size) {
                outcome(
                    ConnectionDiagnosticStatus.WARN,
                    "MCP has ${input.mcpServers.size} entries; the bounded diagnostic inspected the first ${inspectedServers.size}.",
                    "Remove obsolete MCP entries and run diagnostics again to inspect the remainder.",
                )
            } else {
                val enabled = input.mcpServers.count(McpServerConfig::enabled)
                outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "MCP contains $enabled enabled and ${input.mcpServers.size - enabled} disabled configuration entries.",
                )
            }
        }
        inspectedServers.forEachIndexed { index, config ->
            val ordinal = index + 1
            val prefix = "mcp.$ordinal"
            checks += measuredCheck(
                id = "$prefix.configuration",
                category = ConnectionDiagnosticCategory.MCP,
                title = "MCP server $ordinal configuration",
            ) {
                mcpConfigurationOutcome(config)
            }
            if (!config.enabled) return@forEachIndexed
            val credentials = input.mcpCredentialsByServerId[config.id]
                ?: ConnectionDiagnosticsMcpCredentials(inspectionAvailable = false)
            checks += measuredCheck(
                id = "$prefix.credentials",
                category = ConnectionDiagnosticCategory.MCP,
                title = "MCP server $ordinal credentials",
            ) {
                mcpCredentialOutcome(config, credentials)
            }
            if (config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.OAUTH) {
                checks += measuredCheck(
                    id = "$prefix.oauth_metadata",
                    category = ConnectionDiagnosticCategory.MCP,
                    title = "MCP server $ordinal OAuth metadata",
                ) {
                    mcpOAuthOutcome(credentials)
                }
            }
        }
    }

    private fun mcpConfigurationOutcome(config: McpServerConfig): CheckOutcome {
        if (!config.enabled) {
            return outcome(ConnectionDiagnosticStatus.SKIP, "This MCP entry is disabled and was not inspected further.")
        }
        if (config.id.isBlank() || config.id.length > 200) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The MCP entry has an invalid local identifier.",
                "Remove and recreate this MCP configuration entry.",
            )
        }
        return when (config.transport) {
            McpTransport.STDIO -> when {
                config.command.isBlank() -> outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "The stdio MCP command is missing; no process was started.",
                    "Configure an executable command for this MCP server.",
                )
                config.workingDirectory.isBlank() -> outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "The stdio MCP working directory is missing; no process was started.",
                    "Use a project-relative working directory or the default dot directory.",
                )
                config.environmentKeys.any { !isValidMcpEnvironmentKey(it) } -> outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "The stdio MCP configuration contains an invalid environment variable name.",
                    "Use standard environment variable names containing letters, digits and underscores.",
                )
                else -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "The stdio MCP configuration is structurally valid; diagnostics did not start the process.",
                )
            }
            McpTransport.HTTP -> {
                val endpointValid = runCatching { validateMcpHttpEndpoint(config.url) }.isSuccess
                when {
                    !endpointValid -> outcome(
                        ConnectionDiagnosticStatus.FAIL,
                        "The MCP HTTP endpoint is invalid or insecure; no MCP connection was attempted.",
                        "Use HTTPS for remote MCP or loopback HTTP, without embedded credentials or fragments.",
                    )
                    config.httpAuthMode == McpHttpAuthMode.OAUTH &&
                        (config.oauthClientId.length > 2_048 || config.oauthClientId.any(Char::isISOControl)) -> outcome(
                        ConnectionDiagnosticStatus.FAIL,
                        "The OAuth client ID is malformed.",
                        "Use a valid public client ID, or leave it blank for dynamic registration.",
                    )
                    config.httpAuthMode == McpHttpAuthMode.OAUTH && config.oauthScopes.any { !OAUTH_SCOPE_TOKEN.matches(it) } -> outcome(
                        ConnectionDiagnosticStatus.FAIL,
                        "One or more configured OAuth scopes are malformed.",
                        "Remove whitespace/control characters from individual scope tokens.",
                    )
                    else -> outcome(
                        ConnectionDiagnosticStatus.PASS,
                        "The MCP HTTP configuration is structurally valid; diagnostics did not connect or authorize.",
                    )
                }
            }
        }
    }

    private fun mcpCredentialOutcome(
        config: McpServerConfig,
        credentials: ConnectionDiagnosticsMcpCredentials,
    ): CheckOutcome {
        if (!credentials.inspectionAvailable) {
            return outcome(
                ConnectionDiagnosticStatus.WARN,
                "MCP credential presence could not be read from Password Safe; no values were exported.",
                "Unlock or repair Password Safe, then retry diagnostics.",
            )
        }
        return when (config.transport) {
            McpTransport.STDIO -> when {
                config.environmentKeys.isEmpty() -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "This stdio MCP entry does not request stored environment secrets.",
                )
                credentials.configuredEnvironmentSecretCount >= config.environmentKeys.size -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "All requested stdio MCP secret values are present in Password Safe.",
                )
                else -> outcome(
                    ConnectionDiagnosticStatus.WARN,
                    "One or more requested stdio MCP secret values are missing.",
                    "Save the missing values in the MCP credential editor before connecting.",
                )
            }
            McpTransport.HTTP -> when (config.httpAuthMode) {
                McpHttpAuthMode.NONE -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "This MCP HTTP entry is explicitly configured without authentication.",
                )
                McpHttpAuthMode.BEARER -> if (credentials.bearerTokenConfigured) {
                    outcome(ConnectionDiagnosticStatus.PASS, "A Bearer credential is present in Password Safe and was not read into the report.")
                } else {
                    outcome(
                        ConnectionDiagnosticStatus.FAIL,
                        "Bearer authentication is selected but no credential is configured.",
                        "Save a Bearer credential for this MCP entry.",
                    )
                }
                McpHttpAuthMode.OAUTH -> if (credentials.oauth.sessionPresent) {
                    outcome(ConnectionDiagnosticStatus.PASS, "A local OAuth session is present; token values were not exported.")
                } else {
                    outcome(
                        ConnectionDiagnosticStatus.WARN,
                        "OAuth is configured but no local session is present; diagnostics did not start authorization.",
                        "Use the explicit OAuth Login action when you are ready to authorize this MCP server.",
                    )
                }
            }
        }
    }

    private fun mcpOAuthOutcome(credentials: ConnectionDiagnosticsMcpCredentials): CheckOutcome {
        if (!credentials.inspectionAvailable) {
            return outcome(
                ConnectionDiagnosticStatus.WARN,
                "Cached OAuth metadata could not be inspected without exposing credentials.",
                "Unlock Password Safe, then retry diagnostics.",
            )
        }
        val oauth = credentials.oauth
        if (!oauth.sessionPresent) {
            return outcome(
                ConnectionDiagnosticStatus.SKIP,
                "No cached OAuth metadata exists; discovery and authorization were intentionally not started.",
                "Run the explicit OAuth Login action to discover metadata and authorize.",
            )
        }
        if (!oauth.configurationMatches) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "Cached OAuth metadata belongs to an older MCP configuration.",
                "Sign in again so metadata and tokens are bound to the current endpoint and scopes.",
            )
        }
        val complete = oauth.resourceMetadataPresent && oauth.issuerMetadataPresent &&
            oauth.tokenEndpointMetadataPresent && oauth.clientIdPresent && oauth.accessTokenPresent
        if (!complete) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "Cached OAuth metadata/session fields are incomplete.",
                "Clear the local OAuth session and perform OAuth Login again.",
            )
        }
        if (oauth.accessTokenExpired && !oauth.refreshTokenPresent) {
            return outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The cached OAuth access token is expired and no refresh credential is available.",
                "Perform OAuth Login again.",
            )
        }
        if (oauth.accessTokenExpired) {
            return outcome(
                ConnectionDiagnosticStatus.WARN,
                "Cached OAuth metadata is complete; the expired access token can be refreshed on an approved connection.",
                "Connect when ready; diagnostics did not refresh or contact the authorization server.",
            )
        }
        return outcome(
            ConnectionDiagnosticStatus.PASS,
            "Cached OAuth resource, issuer, token endpoint, client and token metadata are complete.",
        )
    }

    private suspend fun addSandboxChecks(
        mode: SandboxMode,
        checks: MutableList<ConnectionDiagnosticCheck>,
    ) {
        checks += measuredCheck(
            id = "sandbox.mode",
            category = ConnectionDiagnosticCategory.SANDBOX,
            title = "Sandbox mode",
        ) {
            when (mode) {
                SandboxMode.WORKSPACE_WRITE -> outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "Workspace-write sandbox mode is selected.",
                )
                SandboxMode.DANGER_FULL_ACCESS -> outcome(
                    ConnectionDiagnosticStatus.WARN,
                    "Danger-full-access mode is selected and does not enforce filesystem or network isolation.",
                    "Switch to workspace-write unless unrestricted execution is explicitly required.",
                )
            }
        }
        checks += measuredCheck(
            id = "sandbox.enforcement",
            category = ConnectionDiagnosticCategory.SANDBOX,
            title = "Workspace sandbox enforcement",
        ) {
            val capability = runCatching { sandboxCapability(SandboxMode.WORKSPACE_WRITE) }.getOrNull()
                ?: return@measuredCheck outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "Workspace sandbox capability detection failed without exporting system details.",
                    "Install or restore the supported OS sandbox backend, then retry.",
                )
            if (capability.available && capability.enforced &&
                capability.enforcement !in setOf(SandboxEnforcement.NONE, SandboxEnforcement.UNAVAILABLE)
            ) {
                outcome(
                    ConnectionDiagnosticStatus.PASS,
                    "An OS-enforced workspace sandbox backend is available.",
                )
            } else {
                outcome(
                    ConnectionDiagnosticStatus.FAIL,
                    "No enforceable workspace sandbox backend is available.",
                    "Install/enable the platform sandbox backend before running Agent commands in workspace-write mode.",
                )
            }
        }
    }

    private suspend fun measuredCheck(
        id: String,
        category: ConnectionDiagnosticCategory,
        title: String,
        evaluate: suspend () -> CheckOutcome,
    ): ConnectionDiagnosticCheck {
        val started = nanoTime()
        val result = try {
            evaluate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            outcome(
                ConnectionDiagnosticStatus.FAIL,
                "The diagnostic check failed without exporting raw system error text.",
                "Retry the diagnostic; if it repeats, export this redacted report for support.",
            )
        }
        return ConnectionDiagnosticCheck(
            id = id,
            category = category,
            title = title,
            status = result.status,
            summary = result.summary,
            durationMillis = elapsedMillis(started),
            recoverySuggestion = result.recoverySuggestion,
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L)

    companion object {
        fun getInstance(): ConnectionDiagnosticsService =
            ApplicationManager.getApplication().getService(ConnectionDiagnosticsService::class.java)

        private fun defaultSandboxCapabilityReader(): (SandboxMode) -> SandboxCapability {
            val sandbox = ProcessSandbox()
            return sandbox::capability
        }

        private val PROXY_CREDENTIAL_PROPERTIES = listOf(
            "http.proxyUser",
            "http.proxyPassword",
            "https.proxyUser",
            "https.proxyPassword",
        )
        private const val MAX_DIAGNOSTIC_MCP_SERVERS = 128
    }
}

private data class CheckOutcome(
    val status: ConnectionDiagnosticStatus,
    val summary: String,
    val recoverySuggestion: String? = null,
)

private enum class LocalCapability {
    YES,
    NO,
    UNKNOWN,
}

private fun outcome(
    status: ConnectionDiagnosticStatus,
    summary: String,
    recoverySuggestion: String? = null,
): CheckOutcome = CheckOutcome(status, summary, recoverySuggestion)

private fun providerEndpoint(baseUrl: String, region: String): URI? {
    val expanded = baseUrl.trim().replace("{region}", region.trim())
    if (modelApiBaseUrlValidationError(expanded) != null) return null
    return runCatching { URI(expanded) }
        .getOrNull()
        ?.takeIf { it.isAbsolute && it.host != null }
}

private fun likelyToolCapability(model: String): LocalCapability {
    val normalized = model.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return LocalCapability.UNKNOWN
    val tokens = normalized.split(Regex("[^a-z0-9]+"))
    val clearlySpecialized = tokens.any {
        it in setOf("embedding", "embeddings", "embed", "moderation", "rerank", "reranker", "tts", "whisper")
    } || (tokens.any { it in setOf("image", "video", "audio") } &&
        tokens.none { it in setOf("vision", "multimodal", "chat", "assistant") })
    if (clearlySpecialized) return LocalCapability.NO
    val known = TOOL_CAPABILITY_MARKERS.any { marker ->
        normalized.startsWith(marker) || normalized.contains("/$marker") || normalized.contains("-$marker")
    } || tokens.any { it in setOf("chat", "coder", "code", "assistant", "instruct") }
    return if (known) LocalCapability.YES else LocalCapability.UNKNOWN
}

private val TOOL_CAPABILITY_MARKERS = listOf(
    "gpt-",
    "o1",
    "o3",
    "o4",
    "claude",
    "gemini",
    "qwen",
    "deepseek",
    "grok",
    "llama",
    "mistral",
    "command",
    "glm",
    "kimi",
    "ernie",
    "hunyuan",
    "doubao",
    "nova",
)
