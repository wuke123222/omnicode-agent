package dev.omnicode.mcp.oauth

import dev.omnicode.mcp.McpHttpAuthorizationChallengeException
import dev.omnicode.mcp.McpStreamableHttpClient
import dev.omnicode.mcp.sha256Hex
import dev.omnicode.settings.McpOAuthCredentialStore
import dev.omnicode.settings.McpOAuthStoredSession
import dev.omnicode.settings.McpOAuthSessionStore
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock

internal data class McpOAuthLoginApproval(
    val serverName: String,
    val resource: URI,
    val issuer: URI,
    val authorizationEndpoint: URI,
    val redirectUri: URI,
    val scopes: Set<String>,
    val dynamicRegistration: Boolean,
)

internal class McpOAuthLoginRejectedException : IllegalStateException("MCP OAuth login was cancelled.")

internal class McpOAuthClientRegistrationRequiredException(message: String) : McpOAuthException(message)

internal class McpOAuthLoginRequiredException(serverName: String) : IllegalStateException(
    "MCP OAuth login is required for '$serverName'. Open MCP Services in the OmniCode sidebar and choose OAuth Login.",
)

/** Coordinates one interactive OAuth login and non-interactive refreshes. */
internal class McpOAuthSessionManager(
    private val discoveryClient: McpOAuthDiscoveryClient = McpOAuthDiscoveryClient(),
    private val registrationClient: McpOAuthDynamicRegistrationClient = McpOAuthDynamicRegistrationClient(),
    private val tokenClient: McpOAuthTokenClient = McpOAuthTokenClient(),
    private val credentialStore: McpOAuthSessionStore = McpOAuthCredentialStore.getInstance(),
    private val clock: Clock = Clock.systemUTC(),
    private val challengeReader: suspend (McpServerConfig) -> List<String> = { config ->
        try {
            McpStreamableHttpClient.connect(config, bearerToken = "").use { Unit }
            emptyList()
        } catch (challenge: McpHttpAuthorizationChallengeException) {
            challenge.wwwAuthenticate
        }
    },
) {
    /**
     * Discovers credential-free OAuth metadata for an explicit configuration UI action.
     * Callers remain responsible for obtaining user approval before this external network read.
     */
    suspend fun discoverConfiguration(
        config: McpServerConfig,
        wwwAuthenticate: List<String> = emptyList(),
    ): McpOAuthConfigurationPreview = withContext(Dispatchers.IO) {
        require(config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.OAUTH) {
            "OAuth discovery requires a Streamable HTTP MCP server using OAuth"
        }
        val endpoint = runCatching { URI(config.url) }.getOrElse {
            throw McpOAuthException("MCP resource URL is invalid", it)
        }
        discoverWithChallengeFallback(config, endpoint, wwwAuthenticate).toConfigurationPreview()
    }

    suspend fun login(
        config: McpServerConfig,
        confirm: suspend (McpOAuthLoginApproval) -> Boolean,
        openBrowser: suspend (URI) -> Unit,
        wwwAuthenticate: List<String> = emptyList(),
    ): McpOAuthStoredSession {
        val ticket = McpOAuthOperationCoordinator.ticket(config.id)
        return McpOAuthOperationCoordinator.withOperation(ticket) {
            withContext(Dispatchers.IO) {
                val endpoint = URI(config.url)
                val discovery = discoverWithChallengeFallback(config, endpoint, wwwAuthenticate)
                if (config.oauthClientId.isBlank()) {
                    requireAutomaticClientRegistration(discovery.clientRegistrationCapability())
                }
                val state = McpOAuthState.generate()
                val pkce = McpOAuthPkce.generate()
                McpOAuthLoopbackCallback.start(state).use { callback ->
                    val requestedScopes = selectScopes(config.oauthScopes.toSet(), discovery)
                    val dynamicRegistration = config.oauthClientId.isBlank()
                    val approval = McpOAuthLoginApproval(
                        serverName = config.name,
                        resource = discovery.resource,
                        issuer = discovery.authorizationServer.issuer,
                        authorizationEndpoint = discovery.authorizationServer.authorizationEndpoint,
                        redirectUri = callback.redirectUri,
                        scopes = requestedScopes,
                        dynamicRegistration = dynamicRegistration,
                    )
                    if (!confirm(approval)) throw McpOAuthLoginRejectedException()

                    val registration = if (dynamicRegistration) {
                        val registrationAuthMethod = compatibleDynamicRegistrationAuthMethod(
                            discovery.authorizationServer.tokenEndpointAuthMethodsSupported,
                        ) ?: throw McpOAuthException(
                            "OAuth server does not support a compatible client authentication method",
                        )
                        registrationClient.register(
                            discovery.authorizationServer,
                            McpDynamicClientRegistrationRequest(
                                redirectUris = listOf(callback.redirectUri),
                                clientName = "OmniCode Agent",
                                tokenEndpointAuthMethod = registrationAuthMethod,
                            ),
                        )
                    } else {
                        McpDynamicClientRegistration(
                            clientId = config.oauthClientId,
                            clientSecret = null,
                            clientSecretExpiresAtEpochSeconds = null,
                            redirectUris = listOf(callback.redirectUri),
                            tokenEndpointAuthMethod = McpTokenEndpointAuthMethod.NONE.wireName,
                        )
                    }
                    requireUsableRegistration(registration, callback.redirectUri)
                    val authMethod = parseAuthMethod(registration.tokenEndpointAuthMethod)
                    val authorizationUri = McpOAuthAuthorization.buildUrl(
                        metadata = discovery.authorizationServer,
                        clientId = registration.clientId,
                        redirectUri = callback.redirectUri,
                        resource = discovery.resource,
                        scopes = requestedScopes,
                        pkce = pkce,
                        state = state,
                    )
                    openBrowser(authorizationUri)
                    val callbackResult = callback.await()
                    if (callbackResult.error != null) {
                        throw McpOAuthException("OAuth authorization failed (${callbackResult.error})")
                    }
                    val code = callbackResult.code
                        ?: throw McpOAuthException("OAuth authorization returned no code")
                    val tokens = tokenClient.exchangeAuthorizationCode(
                        McpAuthorizationCodeRequest(
                            metadata = discovery.authorizationServer,
                            clientId = registration.clientId,
                            clientSecret = registration.clientSecret,
                            tokenEndpointAuthMethod = authMethod,
                            code = code,
                            codeVerifier = pkce.verifier,
                            redirectUri = callback.redirectUri,
                            resource = discovery.resource,
                        ),
                    )
                    val session = McpOAuthStoredSession(
                        configurationBinding = oauthConfigurationBinding(config),
                        resource = discovery.resource.toASCIIString(),
                        issuer = discovery.authorizationServer.issuer.toASCIIString(),
                        tokenEndpoint = discovery.authorizationServer.tokenEndpoint.toASCIIString(),
                        clientId = registration.clientId,
                        clientSecret = registration.clientSecret.orEmpty(),
                        clientSecretExpiresAtEpochSeconds = registration.clientSecretExpiresAtEpochSeconds ?: 0L,
                        tokenEndpointAuthMethod = authMethod.wireName,
                        redirectUri = callback.redirectUri.toASCIIString(),
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken.orEmpty(),
                        tokenType = tokens.tokenType,
                        scopes = tokens.scopes.ifEmpty { requestedScopes }.toList(),
                        expiresAtEpochMillis = tokens.expiresAtEpochMillis ?: 0L,
                    )
                    McpOAuthOperationCoordinator.commitSessionIfCurrent(ticket) {
                        credentialStore.save(config.id, session)
                    }
                    session
                }
            }
        }
    }

    suspend fun accessToken(
        config: McpServerConfig,
        forceRefresh: Boolean = false,
        wwwAuthenticate: List<String> = emptyList(),
    ): String {
        val ticket = McpOAuthOperationCoordinator.ticket(config.id)
        return McpOAuthOperationCoordinator.withOperation(ticket) {
            withContext(Dispatchers.IO) {
                val session = credentialStore.load(config.id)
                    ?: throw McpOAuthLoginRequiredException(config.name)
                val configuredResource = canonicalMcpResource(URI(config.url))
                if (session.configurationBinding != oauthConfigurationBinding(config) ||
                    canonicalMcpResource(URI(session.resource)) != configuredResource
                ) {
                    throw McpOAuthLoginRequiredException(config.name)
                }
                val now = clock.millis()
                if (forceRefresh && McpOAuthOperationCoordinator.sessionChangedSince(ticket)) {
                    return@withContext session.accessToken
                }
                if (!forceRefresh &&
                    (session.expiresAtEpochMillis == 0L || session.expiresAtEpochMillis > now + REFRESH_SKEW_MILLIS)
                ) {
                    return@withContext session.accessToken
                }
                if (session.refreshToken.isBlank()) throw McpOAuthLoginRequiredException(config.name)
                if (session.clientSecretExpiresAtEpochSeconds > 0L &&
                    session.clientSecretExpiresAtEpochSeconds <= now / 1_000L
                ) {
                    throw McpOAuthLoginRequiredException(config.name)
                }

                val discovery = discoverWithChallengeFallback(config, configuredResource, wwwAuthenticate)
                if (!sameIssuer(URI(session.issuer), discovery.authorizationServer.issuer)) {
                    throw McpOAuthException("OAuth issuer changed; sign in again before connecting to this MCP server")
                }
                val authMethod = parseAuthMethod(session.tokenEndpointAuthMethod)
                val refreshed = try {
                    tokenClient.refresh(
                        McpRefreshTokenRequest(
                            metadata = discovery.authorizationServer,
                            clientId = session.clientId,
                            clientSecret = session.clientSecret.takeIf(String::isNotBlank),
                            tokenEndpointAuthMethod = authMethod,
                            refreshToken = session.refreshToken,
                            resource = discovery.resource,
                            scopes = session.scopes.toSet(),
                        ),
                    )
                } catch (error: McpOAuthEndpointException) {
                    if (error.requiresNewLogin) {
                        McpOAuthOperationCoordinator.invalidateIfCurrent(ticket) {
                            credentialStore.clear(config.id)
                        }
                        throw McpOAuthLoginRequiredException(config.name)
                    }
                    throw error
                }
                val updated = session.copy(
                    tokenEndpoint = discovery.authorizationServer.tokenEndpoint.toASCIIString(),
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken ?: session.refreshToken,
                    tokenType = refreshed.tokenType,
                    scopes = refreshed.scopes.ifEmpty { session.scopes.toSet() }.toList(),
                    expiresAtEpochMillis = refreshed.expiresAtEpochMillis ?: 0L,
                )
                McpOAuthOperationCoordinator.commitSessionIfCurrent(ticket) {
                    credentialStore.save(config.id, updated)
                }
                updated.accessToken
            }
        }
    }

    fun logout(serverId: String) = McpOAuthOperationCoordinator.invalidate(serverId) {
        credentialStore.clear(serverId)
    }

    fun hasSession(serverId: String): Boolean = credentialStore.hasSession(serverId)

    fun hasUsableSession(config: McpServerConfig): Boolean = runCatching {
        val session = credentialStore.load(config.id) ?: return false
        session.configurationBinding == oauthConfigurationBinding(config) &&
            canonicalMcpResource(URI(session.resource)) == canonicalMcpResource(URI(config.url))
    }.getOrDefault(false)

    private suspend fun discoverWithChallengeFallback(
        config: McpServerConfig,
        endpoint: URI,
        wwwAuthenticate: List<String>,
    ): McpOAuthDiscoveryResult {
        if (wwwAuthenticate.isNotEmpty()) return discoveryClient.discover(endpoint, wwwAuthenticate)
        return try {
            discoveryClient.discover(endpoint)
        } catch (metadataFailure: McpOAuthException) {
            val challenge = challengeReader(config)
            if (challenge.isEmpty()) throw metadataFailure
            discoveryClient.discover(endpoint, challenge)
        }
    }

    private fun requireUsableRegistration(registration: McpDynamicClientRegistration, redirectUri: URI) {
        if (redirectUri !in registration.redirectUris) {
            throw McpOAuthException("OAuth registration did not accept the loopback redirect URI")
        }
        val expires = registration.clientSecretExpiresAtEpochSeconds
        if (expires != null && expires > 0L && expires <= clock.instant().epochSecond) {
            throw McpOAuthException("OAuth dynamic client registration returned an expired client secret")
        }
    }

    private fun parseAuthMethod(value: String): McpTokenEndpointAuthMethod =
        McpTokenEndpointAuthMethod.entries.firstOrNull { it.wireName == value }
            ?: throw McpOAuthException("OAuth server selected an unsupported client authentication method")

    private fun selectScopes(
        configured: Set<String>,
        discovery: McpOAuthDiscoveryResult,
    ): Set<String> {
        val selected = when {
            discovery.challengeScopes.isNotEmpty() -> discovery.challengeScopes + configured
            configured.isNotEmpty() -> configured
            else -> discovery.requestedScopes
        }
        if (selected.size > MAX_SELECTED_SCOPES) {
            throw McpOAuthException("OAuth scope count exceeds the supported limit")
        }
        return selected.toCollection(linkedSetOf())
    }

    private fun sameIssuer(left: URI, right: URI): Boolean =
        runCatching { left.toASCIIString() == right.toASCIIString() }.getOrDefault(false)

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
        const val MAX_SELECTED_SCOPES = 128
    }
}

internal fun McpOAuthDiscoveryResult.toConfigurationPreview(): McpOAuthConfigurationPreview =
    McpOAuthConfigurationPreview(
        resource = resource,
        protectedResourceMetadataUri = protectedResource.metadataUri,
        issuer = authorizationServer.issuer,
        authorizationServerMetadataUri = authorizationServer.metadataUri,
        authorizationEndpoint = authorizationServer.authorizationEndpoint,
        tokenEndpoint = authorizationServer.tokenEndpoint,
        registrationEndpoint = authorizationServer.registrationEndpoint,
        scopes = requestedScopes,
        clientRegistrationCapability = clientRegistrationCapability(),
    )

internal fun McpOAuthDiscoveryResult.clientRegistrationCapability(): McpOAuthClientRegistrationCapability = when {
    authorizationServer.registrationEndpoint != null &&
        compatibleDynamicRegistrationAuthMethod(authorizationServer.tokenEndpointAuthMethodsSupported) != null ->
        McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION
    authorizationServer.registrationEndpoint != null ->
        McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION_INCOMPATIBLE
    authorizationServer.clientIdMetadataDocumentSupported ->
        McpOAuthClientRegistrationCapability.CLIENT_ID_METADATA_DOCUMENT
    else -> McpOAuthClientRegistrationCapability.MANUAL_CLIENT_ID
}

internal fun compatibleDynamicRegistrationAuthMethod(
    supported: Set<String>,
): McpTokenEndpointAuthMethod? = when {
    supported.isEmpty() || McpTokenEndpointAuthMethod.NONE.wireName in supported -> McpTokenEndpointAuthMethod.NONE
    McpTokenEndpointAuthMethod.CLIENT_SECRET_POST.wireName in supported ->
        McpTokenEndpointAuthMethod.CLIENT_SECRET_POST
    else -> null
}

private fun requireAutomaticClientRegistration(capability: McpOAuthClientRegistrationCapability) {
    when (capability) {
        McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION -> Unit
        McpOAuthClientRegistrationCapability.CLIENT_ID_METADATA_DOCUMENT ->
            throw McpOAuthClientRegistrationRequiredException(
                "This server requires a pre-registered OAuth Client ID. Enter the provider's public Client ID in " +
                    "OAuth Client ID, save, then choose OAuth Login. Client ID Metadata Documents are advertised " +
                    "but are not supported yet.",
            )
        McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION_INCOMPATIBLE ->
            throw McpOAuthClientRegistrationRequiredException(
                "This server's Dynamic Client Registration requires an unsupported client authentication method. " +
                    "Enter the provider's public Client ID in OAuth Client ID, save, then choose OAuth Login.",
            )
        McpOAuthClientRegistrationCapability.MANUAL_CLIENT_ID ->
            throw McpOAuthClientRegistrationRequiredException(
                "This server does not support Dynamic Client Registration. Enter the provider's public Client ID " +
                    "in OAuth Client ID, save, then choose OAuth Login.",
            )
    }
}

internal fun oauthConfigurationBinding(config: McpServerConfig): String = sha256Hex(
    buildString {
        append("mcp-oauth\u0000v1\u0000")
        append(canonicalMcpResource(URI(config.url)).toASCIIString()).append('\u0000')
        append(config.httpAuthMode.id).append('\u0000')
        append(config.oauthClientId.trim()).append('\u0000')
        append(config.oauthScopes.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            .joinToString(" "))
    }.toByteArray(StandardCharsets.UTF_8),
)
