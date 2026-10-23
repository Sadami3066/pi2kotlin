package dev.pi.mcp.oauth

import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.auth.oauth.generatePkce
import dev.pi.ai.auth.oauth.oauthRandomBytes
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.platform.platformSecureRandomBytes
import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

const val OAUTH_REQUEST_TIMEOUT_MS: Long = 15_000L

private const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

data class AuthorizationServerDiscoveryUrl(
    val url: String,
    val type: String,
)

class OAuthServerInfo(
    val authorizationServerUrl: String,
    val authorizationServerMetadata: AuthorizationServerMetadata?,
    val resourceMetadata: OAuthProtectedResourceMetadata?,
)

data class AuthorizationRequest(
    val authorizationUrl: String,
    val codeVerifier: String,
    val state: String?,
    val redirectUri: String,
    val resource: String?,
)

private class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
) {
    val origin: String
        get() {
            val hostText = if (host.contains(":")) "[$host]" else host
            return if (port == null) "$scheme://$hostText" else "$scheme://$hostText:$port"
        }

    fun resolve(pathAndQuery: String): String = origin + pathAndQuery

    companion object {
        fun parse(value: String): ParsedUrl {
            val scheme = value.substringBefore("://", "")
            if (scheme.isEmpty()) throw OAuthFlowException("Invalid URL: $value")
            val rest = value.substringAfter("://")
            val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'))
            val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
            val pathAndQuery = if (authorityEnd < 0) "/" else rest.substring(authorityEnd)
            val path = pathAndQuery.substringBefore('?').substringBefore('#').ifEmpty { "/" }
            val host: String
            val port: Int?
            if (authority.startsWith("[")) {
                val closing = authority.indexOf(']')
                if (closing < 0) throw OAuthFlowException("Invalid URL: $value")
                host = authority.substring(1, closing)
                val remainder = authority.substring(closing + 1)
                port = if (remainder.startsWith(":")) remainder.substring(1).toIntOrNull() else null
            } else {
                host = authority.substringBefore(':')
                port = if (authority.contains(':')) authority.substringAfter(':').toIntOrNull() else null
            }
            if (host.isEmpty()) throw OAuthFlowException("Invalid URL: $value")
            return ParsedUrl(scheme = scheme, host = host, port = port, path = path)
        }
    }
}

fun isLoopbackHost(host: String): Boolean {
    val normalized = host.removePrefix("[").removeSuffix("]")
    return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
}

private fun pathSuffix(path: String): String = if (path.endsWith("/")) path.dropLast(1) else path

private fun requireSecureUrl(value: String): String {
    val parsed = ParsedUrl.parse(value)
    if (parsed.scheme != "https" && !isLoopbackHost(parsed.host)) {
        throw OAuthInsecureEndpointException(value)
    }
    return value
}

private fun isDiscoveryMiss(status: Int): Boolean = (status in 400..499) || status == 502

fun buildAuthorizationServerDiscoveryUrls(authorizationServerUrl: String): List<AuthorizationServerDiscoveryUrl> {
    val issuer = ParsedUrl.parse(authorizationServerUrl)
    val suffix = pathSuffix(issuer.path)
    val urls = mutableListOf(
        AuthorizationServerDiscoveryUrl(issuer.resolve("/.well-known/oauth-authorization-server$suffix"), "oauth"),
        AuthorizationServerDiscoveryUrl(issuer.resolve("/.well-known/openid-configuration$suffix"), "oidc"),
    )
    if (suffix.isNotEmpty()) {
        urls += AuthorizationServerDiscoveryUrl(issuer.resolve("$suffix/.well-known/openid-configuration"), "oidc")
    }
    return urls.map { AuthorizationServerDiscoveryUrl(requireSecureUrl(it.url), it.type) }
}

fun resourceUrlFromServerUrl(serverUrl: String): String = serverUrl.substringBefore('#')

fun selectResource(serverUrl: String, metadata: OAuthProtectedResourceMetadata?): String? {
    if (metadata == null) return null
    val requested = ParsedUrl.parse(resourceUrlFromServerUrl(serverUrl))
    val configured = ParsedUrl.parse(metadata.resource)
    if (requested.origin != configured.origin) {
        throw OAuthFlowException(
            "Protected resource ${metadata.resource} does not match MCP server ${resourceUrlFromServerUrl(serverUrl)}",
        )
    }
    val requestedPath = if (requested.path.endsWith("/")) requested.path else "${requested.path}/"
    val configuredPath = if (configured.path.endsWith("/")) configured.path else "${configured.path}/"
    if (!requestedPath.startsWith(configuredPath)) {
        throw OAuthFlowException(
            "Protected resource ${metadata.resource} does not match MCP server ${resourceUrlFromServerUrl(serverUrl)}",
        )
    }
    return metadata.resource
}

fun stepUpScope(granted: String?, challenged: String?): String? {
    if (challenged.isNullOrEmpty()) return null
    val scopes = LinkedHashSet<String>()
    for (scope in listOf(granted, challenged)) {
        scope?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.forEach { scopes += it }
    }
    return if (scopes.isEmpty()) null else scopes.joinToString(" ")
}

private fun parseJsonBody(body: String, name: String): JsonElement =
    runCatching { PiJson.parseToJsonElement(body) }.getOrNull()
        ?: throw OAuthFlowException("Invalid $name")

private suspend fun fetchOAuthText(
    transport: HttpTransport,
    url: String,
    signal: RunSignal?,
): Pair<Int, String> {
    val response = transport.execute(
        HttpRequest(
            method = "GET",
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                MCP_PROTOCOL_VERSION_HEADER to LATEST_PROTOCOL_VERSION,
            ),
            timeoutMs = OAUTH_REQUEST_TIMEOUT_MS,
            signal = signal,
        ),
    )
    return try {
        response.status to response.body.readUtf8()
    } finally {
        response.close()
    }
}

suspend fun discoverProtectedResourceMetadata(
    serverUrl: String,
    transport: HttpTransport,
    resourceMetadataUrl: String? = null,
    signal: RunSignal? = null,
): OAuthProtectedResourceMetadata {
    val server = ParsedUrl.parse(serverUrl)
    var url = if (resourceMetadataUrl != null) {
        requireSecureUrl(resourceMetadataUrl)
    } else {
        requireSecureUrl(server.resolve("/.well-known/oauth-protected-resource${pathSuffix(server.path)}"))
    }
    var response = fetchOAuthText(transport, url, signal)
    if (resourceMetadataUrl == null && server.path != "/" && isDiscoveryMiss(response.first)) {
        url = requireSecureUrl(server.resolve("/.well-known/oauth-protected-resource"))
        response = fetchOAuthText(transport, url, signal)
    }
    if (response.first !in 200..299) {
        throw OAuthDiscoveryException("HTTP ${response.first} loading OAuth protected resource metadata")
    }
    return parseProtectedResourceMetadata(parseJsonBody(response.second, "OAuth protected resource metadata"))
}

suspend fun discoverAuthorizationServerMetadata(
    authorizationServerUrl: String,
    transport: HttpTransport,
    skipIssuerValidation: Boolean = false,
    signal: RunSignal? = null,
): AuthorizationServerMetadata? {
    for (candidate in buildAuthorizationServerDiscoveryUrls(authorizationServerUrl)) {
        val response = fetchOAuthText(transport, candidate.url, signal)
        if (response.first !in 200..299) {
            if (isDiscoveryMiss(response.first)) continue
            throw OAuthDiscoveryException(
                "HTTP ${response.first} loading authorization server metadata from ${candidate.url}",
            )
        }
        val metadata = parseAuthorizationServerMetadata(
            parseJsonBody(response.second, "authorization server metadata"),
        )
        if (!skipIssuerValidation) {
            if (trimTrailingSlash(metadata.issuer) != trimTrailingSlash(authorizationServerUrl)) {
                throw OAuthIssuerMismatchException(authorizationServerUrl, metadata.issuer)
            }
        }
        return metadata
    }
    return null
}

private fun trimTrailingSlash(value: String): String = if (value.endsWith("/")) value.dropLast(1) else value

suspend fun discoverOAuthServerInfo(
    serverUrl: String,
    transport: HttpTransport,
    resourceMetadataUrl: String? = null,
    authorizationServerMetadataUrl: String? = null,
    skipIssuerValidation: Boolean = false,
    signal: RunSignal? = null,
): OAuthServerInfo {
    var resourceMetadata: OAuthProtectedResourceMetadata? = null
    try {
        resourceMetadata = discoverProtectedResourceMetadata(serverUrl, transport, resourceMetadataUrl, signal)
    } catch (error: OAuthInsecureEndpointException) {
        throw error
    } catch (error: HttpTransportException) {
        throw OAuthDiscoveryException("Failed to load OAuth protected resource metadata", error)
    } catch (error: OAuthFlowException) {
        resourceMetadata = null
    }

    if (authorizationServerMetadataUrl != null) {
        val url = requireSecureUrl(authorizationServerMetadataUrl)
        val response = fetchOAuthText(transport, url, signal)
        if (response.first !in 200..299) {
            throw OAuthDiscoveryException("HTTP ${response.first} loading authorization server metadata from $url")
        }
        val metadata = parseAuthorizationServerMetadata(
            parseJsonBody(response.second, "authorization server metadata"),
        )
        return OAuthServerInfo(metadata.issuer, metadata, resourceMetadata)
    }

    val server = ParsedUrl.parse(serverUrl)
    val authorizationServerUrl = resourceMetadata?.authorizationServers?.firstOrNull() ?: "${server.origin}/"
    return OAuthServerInfo(
        authorizationServerUrl = authorizationServerUrl,
        authorizationServerMetadata = discoverAuthorizationServerMetadata(
            authorizationServerUrl,
            transport,
            skipIssuerValidation,
            signal,
        ),
        resourceMetadata = resourceMetadata,
    )
}

suspend fun discover(
    serverUrl: String,
    transport: HttpTransport,
    metadataUrlOverride: String? = null,
    signal: RunSignal? = null,
): AuthorizationServerMetadata {
    if (metadataUrlOverride != null) {
        val url = requireSecureUrl(metadataUrlOverride)
        val response = fetchOAuthText(transport, url, signal)
        if (response.first !in 200..299) {
            throw OAuthDiscoveryException("HTTP ${response.first} loading authorization server metadata from $url")
        }
        return parseAuthorizationServerMetadata(
            parseJsonBody(response.second, "authorization server metadata"),
        )
    }
    val info = discoverOAuthServerInfo(serverUrl, transport, signal = signal)
    return info.authorizationServerMetadata
        ?: throw OAuthDiscoveryException("Failed to discover OAuth authorization server metadata for $serverUrl")
}

suspend fun registerClient(
    authorizationServerUrl: String,
    transport: HttpTransport,
    metadata: AuthorizationServerMetadata?,
    clientMetadata: OAuthClientMetadata,
    scope: String? = null,
    signal: RunSignal? = null,
): OAuthClientInformation {
    if (metadata != null && metadata.registrationEndpoint == null) {
        throw OAuthFlowException("Authorization server does not support dynamic client registration")
    }
    val endpoint = requireSecureUrl(
        metadata?.registrationEndpoint ?: "${ParsedUrl.parse(authorizationServerUrl).origin}/register",
    )
    val body = buildJsonObject {
        put("redirect_uris", buildJsonArray { clientMetadata.redirectUris.forEach { add(it) } })
        put("application_type", clientMetadata.applicationType ?: applicationType(clientMetadata.redirectUris))
        clientMetadata.clientName?.let { put("client_name", it) }
        clientMetadata.tokenEndpointAuthMethod?.let { put("token_endpoint_auth_method", it) }
        put("grant_types", buildJsonArray { clientMetadata.grantTypes.forEach { add(it) } })
        put("response_types", buildJsonArray { clientMetadata.responseTypes.forEach { add(it) } })
        val effectiveScope = scope ?: clientMetadata.scope
        effectiveScope?.let { put("scope", it) }
    }
    val response = transport.execute(
        HttpRequest(
            method = "POST",
            url = endpoint,
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            body = body.toString().encodeToByteArray(),
            timeoutMs = OAUTH_REQUEST_TIMEOUT_MS,
            signal = signal,
        ),
    )
    val text = try {
        response.body.readUtf8()
    } finally {
        response.close()
    }
    if (response.status !in 200..299) throw OAuthRegistrationException(response.status, text)
    return parseClientInformation(parseJsonBody(text, "OAuth client registration response"))
}

private fun applicationType(redirectUris: List<String>): String {
    val native = redirectUris.any { uri ->
        val parsed = runCatching { ParsedUrl.parse(uri) }.getOrNull() ?: return@any false
        (parsed.scheme != "http" && parsed.scheme != "https") || isLoopbackHost(parsed.host)
    }
    return if (native) "native" else "web"
}

fun startAuthorization(
    authorizationServerUrl: String,
    metadata: AuthorizationServerMetadata?,
    clientInformation: OAuthClientInformation,
    redirectUrl: String,
    scope: String? = null,
    state: String? = null,
    resource: String? = null,
    random: (Int) -> ByteArray = ::oauthRandomBytes,
): AuthorizationRequest {
    if (metadata != null) {
        if (!metadata.responseTypesSupported.contains("code")) {
            throw OAuthFlowException("Authorization server does not support authorization codes")
        }
        val methods = metadata.codeChallengeMethodsSupported
        if (methods != null && !methods.contains("S256")) {
            throw OAuthFlowException("Authorization server does not support PKCE S256")
        }
    }
    val endpoint = requireSecureUrl(
        metadata?.authorizationEndpoint ?: "${ParsedUrl.parse(authorizationServerUrl).origin}/authorize",
    )
    val pkce = generatePkce(random)
    val effectiveState = state ?: randomHex(32)
    val params = mutableListOf(
        "response_type" to "code",
        "client_id" to clientInformation.clientId,
        "code_challenge" to pkce.challenge,
        "code_challenge_method" to "S256",
        "redirect_uri" to redirectUrl,
    )
    params += "state" to effectiveState
    scope?.takeIf { it.isNotEmpty() }?.let { params += "scope" to it }
    if (scope?.split(Regex("\\s+"))?.contains("offline_access") == true) {
        params += "prompt" to "consent"
    }
    resource?.let { params += "resource" to it }
    return AuthorizationRequest(
        authorizationUrl = appendQuery(endpoint, params),
        codeVerifier = pkce.verifier,
        state = effectiveState,
        redirectUri = redirectUrl,
        resource = resource,
    )
}

fun authorize(
    authorizationServerUrl: String,
    metadata: AuthorizationServerMetadata?,
    clientInformation: OAuthClientInformation,
    redirectUrl: String,
    scope: String? = null,
    state: String? = null,
    resource: String? = null,
    random: (Int) -> ByteArray = ::oauthRandomBytes,
): AuthorizationRequest = startAuthorization(
    authorizationServerUrl,
    metadata,
    clientInformation,
    redirectUrl,
    scope,
    state,
    resource,
    random,
)

suspend fun exchangeCode(
    authorizationServerUrl: String,
    transport: HttpTransport,
    metadata: AuthorizationServerMetadata?,
    clientInformation: OAuthClientInformation,
    code: String,
    codeVerifier: String,
    redirectUrl: String,
    resource: String? = null,
    signal: RunSignal? = null,
): OAuthTokens = tokenRequest(
    authorizationServerUrl,
    transport,
    metadata,
    clientInformation,
    resource,
    listOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to codeVerifier,
        "redirect_uri" to redirectUrl,
    ),
    signal,
)

suspend fun refresh(
    authorizationServerUrl: String,
    transport: HttpTransport,
    metadata: AuthorizationServerMetadata?,
    clientInformation: OAuthClientInformation,
    refreshToken: String,
    resource: String? = null,
    signal: RunSignal? = null,
): OAuthTokens {
    val tokens = tokenRequest(
        authorizationServerUrl,
        transport,
        metadata,
        clientInformation,
        resource,
        listOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        signal,
    )
    return if (tokens.refreshToken == null) tokens.copy(refreshToken = refreshToken) else tokens
}

private enum class ClientAuthMethod { Basic, Post, None }

private fun selectClientAuthMethod(
    clientInformation: OAuthClientInformation,
    supported: List<String>,
): ClientAuthMethod {
    val hinted = clientInformation.tokenEndpointAuthMethod
    if (hinted != null && supported.isEmpty() || hinted != null && supported.contains(hinted)) {
        when (hinted) {
            "client_secret_basic" -> return ClientAuthMethod.Basic
            "client_secret_post" -> return ClientAuthMethod.Post
            "none" -> return ClientAuthMethod.None
        }
    }
    val secret = clientInformation.clientSecret
    if (supported.isEmpty()) return if (secret != null) ClientAuthMethod.Basic else ClientAuthMethod.None
    if (secret != null && supported.contains("client_secret_basic")) return ClientAuthMethod.Basic
    if (secret != null && supported.contains("client_secret_post")) return ClientAuthMethod.Post
    if (supported.contains("none")) return ClientAuthMethod.None
    return if (secret != null) ClientAuthMethod.Post else ClientAuthMethod.None
}

private suspend fun tokenRequest(
    authorizationServerUrl: String,
    transport: HttpTransport,
    metadata: AuthorizationServerMetadata?,
    clientInformation: OAuthClientInformation,
    resource: String?,
    params: List<Pair<String, String>>,
    signal: RunSignal?,
): OAuthTokens {
    val endpoint = requireSecureUrl(
        metadata?.tokenEndpoint ?: "${ParsedUrl.parse(authorizationServerUrl).origin}/token",
    )
    val headers = linkedMapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/x-www-form-urlencoded",
    )
    val form = params.toMutableList()
    if (resource != null) form += "resource" to resource
    when (selectClientAuthMethod(clientInformation, metadata?.tokenEndpointAuthMethodsSupported.orEmpty())) {
        ClientAuthMethod.Basic -> {
            val secret = clientInformation.clientSecret
                ?: throw OAuthFlowException("client_secret_basic requires a client secret")
            val encoded = Base64.Default.encode(
                "${clientInformation.clientId}:$secret".encodeToByteArray(),
            )
            headers["Authorization"] = "Basic $encoded"
        }

        ClientAuthMethod.Post -> {
            form += "client_id" to clientInformation.clientId
            clientInformation.clientSecret?.let { form += "client_secret" to it }
        }

        ClientAuthMethod.None -> form += "client_id" to clientInformation.clientId
    }
    val response = transport.execute(
        HttpRequest(
            method = "POST",
            url = endpoint,
            headers = headers,
            body = encodeForm(form).encodeToByteArray(),
            timeoutMs = OAUTH_REQUEST_TIMEOUT_MS,
            signal = signal,
        ),
    )
    val text = try {
        response.body.readUtf8()
    } finally {
        response.close()
    }
    val parsed = runCatching { PiJson.parseToJsonElement(text) }.getOrNull()
    if (parsed is JsonObject) {
        val error = (parsed["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!error.isNullOrEmpty()) {
            throw OAuthResponseException(
                code = error,
                message = (parsed["error_description"] as? JsonPrimitive)?.contentOrNull ?: error,
                errorUri = (parsed["error_uri"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
    if (response.status !in 200..299) {
        throw OAuthResponseException("server_error", "HTTP ${response.status}: $text")
    }
    return parseOAuthTokens(parsed ?: JsonNull)
}

internal fun encodeForm(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) -> "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}" }

internal fun encodeQueryComponent(value: String): String {
    val output = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '*' || char == '-' ||
            char == '.' || char == '_'
        ) {
            output.append(char)
        } else if (char == ' ') {
            output.append('+')
        } else {
            output.append('%')
            output.append(HEX_UPPER[code shr 4])
            output.append(HEX_UPPER[code and 0x0F])
        }
    }
    return output.toString()
}

private const val HEX_UPPER = "0123456789ABCDEF"

internal fun appendQuery(base: String, params: List<Pair<String, String>>): String {
    if (params.isEmpty()) return base
    val separator = if (base.contains('?')) "&" else "?"
    return base + separator + encodeForm(params)
}

internal fun parseUrlQuery(url: String): Map<String, String> {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in query.split("&")) {
        if (part.isEmpty()) continue
        val name = percentDecode(part.substringBefore('='))
        val value = percentDecode(if (part.contains('=')) part.substringAfter('=') else "")
        result[name] = value
    }
    return result
}

internal fun percentDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '+' -> {
                bytes += ' '.code.toByte()
                index++
            }

            char == '%' && index + 2 < value.length -> {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded == null) {
                    bytes += char.code.toByte()
                    index++
                } else {
                    bytes += decoded.toByte()
                    index += 3
                }
            }

            else -> {
                bytes += char.code.toByte()
                index++
            }
        }
    }
    return ByteArray(bytes.size) { bytes[it] }.decodeToString()
}

internal fun randomHex(byteCount: Int): String {
    val bytes = platformSecureRandomBytes(byteCount)
    return buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX_UPPER[value shr 4].lowercaseChar())
            append(HEX_UPPER[value and 0x0F].lowercaseChar())
        }
    }
}

internal fun tokensExpireAt(tokens: OAuthTokens, nowMillis: Long): Long? =
    tokens.expiresIn?.let { nowMillis + it * 1000 }

internal fun nowMillis(): Long = platformCurrentTimeMillis()

suspend fun signInMcpServer(
    options: McpOAuthOptions,
    redirectUri: String,
    interaction: McpOAuthInteraction,
    transport: HttpTransport,
    tokenStore: McpTokenStore,
    signal: RunSignal? = null,
): OAuthTokens {
    val stored = tokenStore.read(options.serverName, options.serverUrl)
    var resourceMetadata: OAuthProtectedResourceMetadata? = null
    try {
        resourceMetadata = discoverProtectedResourceMetadata(
            options.serverUrl,
            transport,
            options.resourceMetadataUrl,
            signal,
        )
    } catch (error: OAuthInsecureEndpointException) {
        throw error
    } catch (error: HttpTransportException) {
        throw OAuthDiscoveryException("Failed to load OAuth protected resource metadata", error)
    } catch (error: OAuthFlowException) {
        resourceMetadata = null
    }
    val metadata = discover(options.serverUrl, transport, options.authorizationServerMetadataUrl, signal)
    val authorizationServerUrl = metadata.issuer
    val resource = selectResource(options.serverUrl, resourceMetadata)
    val scope = options.scope
        ?: resourceMetadata?.scopesSupported?.takeIf { it.isNotEmpty() }?.joinToString(" ")
    val client = stored?.clientInformation ?: configuredClientInformation(options) ?: run {
        val client = registerClient(
            authorizationServerUrl,
            transport,
            metadata,
            OAuthClientMetadata(
                redirectUris = listOf(redirectUri),
                tokenEndpointAuthMethod = if (options.clientSecret != null) "client_secret_post" else "none",
                clientName = options.clientName,
                scope = scope,
            ),
            scope,
            signal,
        )
        client
    }
    val state = randomHex(32)
    val request = startAuthorization(
        authorizationServerUrl = authorizationServerUrl,
        metadata = metadata,
        clientInformation = client,
        redirectUrl = redirectUri,
        scope = scope,
        state = state,
        resource = resource,
    )
    tokenStore.write(
        options.serverName,
        options.serverUrl,
        (stored ?: McpOAuthState(serverUrl = options.serverUrl)).copy(
            serverUrl = options.serverUrl,
            clientInformation = client,
            codeVerifier = request.codeVerifier,
            oauthState = state,
            discovery = OAuthDiscoveryState(
                authorizationServerUrl = authorizationServerUrl,
                authorizationServerMetadata = metadata,
                resourceMetadata = resourceMetadata,
                resourceMetadataUrl = options.resourceMetadataUrl,
            ),
        ),
    )
    val callbackUrl = interaction.authorize(request.authorizationUrl, request.redirectUri)
    val params = parseUrlQuery(callbackUrl)
    val error = params["error"]
    if (!error.isNullOrEmpty()) {
        throw OAuthResponseException(error, params["error_description"] ?: error, params["error_uri"])
    }
    if (params["state"] != state) throw OAuthFlowException("OAuth state mismatch")
    val issuer = params["iss"]
    if (issuer != null || metadata.authorizationResponseIssParameterSupported) {
        if (issuer != metadata.issuer) throw OAuthIssuerMismatchException(metadata.issuer, issuer)
    }
    val code = params["code"]
    if (code.isNullOrEmpty()) throw OAuthFlowException("OAuth callback did not include an authorization code")
    val tokens = exchangeCode(
        authorizationServerUrl,
        transport,
        metadata,
        client,
        code,
        request.codeVerifier,
        request.redirectUri,
        resource,
        signal,
    )
    val completed = if (tokens.scope == null && scope != null) tokens.copy(scope = scope) else tokens
    tokenStore.write(
        options.serverName,
        options.serverUrl,
        (tokenStore.read(options.serverName, options.serverUrl) ?: McpOAuthState(options.serverUrl)).copy(
            serverUrl = options.serverUrl,
            clientInformation = client,
            tokens = completed,
            tokensExpireAt = tokensExpireAt(completed, nowMillis()),
            codeVerifier = null,
            oauthState = null,
        ),
    )
    return completed
}

private fun configuredClientInformation(options: McpOAuthOptions): OAuthClientInformation? {
    val clientId = options.clientId ?: return null
    return OAuthClientInformation(
        clientId = clientId,
        clientSecret = options.clientSecret,
        tokenEndpointAuthMethod = if (options.clientSecret != null) "client_secret_post" else "none",
    )
}
