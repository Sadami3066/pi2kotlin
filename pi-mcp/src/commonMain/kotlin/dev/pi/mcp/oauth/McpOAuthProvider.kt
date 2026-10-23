package dev.pi.mcp.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.http.HttpTransport
import dev.pi.mcp.McpAuthRequiredException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val TOKEN_REFRESH_SKEW_MS: Long = 30_000L

interface McpAuthProvider {
    suspend fun token(): String?

    suspend fun onUnauthorized(context: McpUnauthorizedContext)
}

data class McpUnauthorizedContext(
    val serverUrl: String,
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val token: String? = null,
) {
    fun wwwAuthenticate(): String? =
        headers.entries.firstOrNull { it.key.equals("www-authenticate", ignoreCase = true) }?.value
}

class McpOAuthProvider(
    val options: McpOAuthOptions,
    private val transport: HttpTransport,
    private val tokenStore: McpTokenStore,
    private val onChallenge: ((OAuthChallenge) -> Unit)? = null,
    private val now: () -> Long = ::nowMillis,
) : McpAuthProvider {
    private val refreshMutex = Mutex()

    override suspend fun token(): String? {
        var state = tokenStore.read(options.serverName, options.serverUrl) ?: return null
        val tokens = state.tokens ?: return null
        val expiresAt = state.tokensExpireAt
        if (tokens.refreshToken != null && expiresAt != null && expiresAt - TOKEN_REFRESH_SKEW_MS <= now()) {
            runCatching { refreshTokens(tokens.accessToken, null) }
            state = tokenStore.read(options.serverName, options.serverUrl) ?: return null
        }
        return state.tokens?.accessToken
    }

    override suspend fun onUnauthorized(context: McpUnauthorizedContext) {
        val header = context.wwwAuthenticate()
        val challenge = WwwAuthenticate.parse(header)
        onChallenge?.invoke(challenge)
        if (challenge.error == "insufficient_scope") {
            throw McpAuthRequiredException(header, options.serverUrl)
        }
        try {
            refreshTokens(context.token, header)
        } catch (error: McpAuthRequiredException) {
            throw error
        } catch (error: Throwable) {
            throw McpAuthRequiredException(
                wwwAuthenticate = header,
                serverUrl = options.serverUrl,
                message = "MCP OAuth refresh failed: ${error.message ?: error.toString()}",
            )
        }
    }

    suspend fun settled() {
        refreshMutex.withLock { }
    }

    suspend fun currentScope(): String? =
        tokenStore.read(options.serverName, options.serverUrl)?.tokens?.scope

    suspend fun signIn(
        interaction: McpOAuthInteraction,
        redirectUri: String,
        signal: RunSignal? = null,
    ): OAuthTokens = signInMcpServer(options, redirectUri, interaction, transport, tokenStore, signal)

    private suspend fun refreshTokens(staleToken: String?, wwwAuthenticate: String?) {
        refreshMutex.withLock {
            val state = tokenStore.read(options.serverName, options.serverUrl)
                ?: throw McpAuthRequiredException(wwwAuthenticate, options.serverUrl)
            val tokens = state.tokens
                ?: throw McpAuthRequiredException(wwwAuthenticate, options.serverUrl)
            if (staleToken != null && tokens.accessToken != staleToken) return@withLock
            val refreshToken = tokens.refreshToken
                ?: throw McpAuthRequiredException(wwwAuthenticate, options.serverUrl)
            val client = state.clientInformation
                ?: throw McpAuthRequiredException(wwwAuthenticate, options.serverUrl)
            val discovery = state.discovery
            val metadata = discovery?.authorizationServerMetadata
                ?: discover(options.serverUrl, transport, options.authorizationServerMetadataUrl)
            val authorizationServerUrl = discovery?.authorizationServerUrl ?: metadata.issuer
            val resource = selectResource(options.serverUrl, discovery?.resourceMetadata)
            val refreshed = refresh(
                authorizationServerUrl = authorizationServerUrl,
                transport = transport,
                metadata = metadata,
                clientInformation = client,
                refreshToken = refreshToken,
                resource = resource,
            )
            val expireAt = tokensExpireAt(refreshed, now())
            tokenStore.write(
                options.serverName,
                options.serverUrl,
                state.copy(tokens = refreshed, tokensExpireAt = expireAt),
            )
        }
    }
}
