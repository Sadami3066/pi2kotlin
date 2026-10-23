package dev.pi.mcp.oauth

import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Buffer

class FakeOAuthResponse(
    val status: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

class FakeOAuthTransport(
    private val handler: suspend (HttpRequest) -> FakeOAuthResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val response = handler(request)
        return HttpResponse(response.status, response.headers, Buffer().writeUtf8(response.body))
    }
}

fun oauthJsonResponse(body: String, status: Int = 200): FakeOAuthResponse =
    FakeOAuthResponse(status, body, mapOf("content-type" to "application/json"))

fun HttpRequest.bodyText(): String = body?.decodeToString().orEmpty()

fun HttpRequest.formBody(): Map<String, String> = parseForm(bodyText())

fun HttpRequest.jsonBody(): JsonObject = dev.pi.ai.PiJson.parseToJsonElement(bodyText()).jsonObject

fun HttpRequest.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun parseForm(text: String): Map<String, String> {
    if (text.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in text.split("&")) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('=')
        val value = if (part.contains('=')) part.substringAfter('=') else ""
        result[percentDecode(name)] = percentDecode(value)
    }
    return result
}

fun deterministicRandom(): (Int) -> ByteArray = { count -> ByteArray(count) { 7 } }

fun deterministicVerifier(): String = dev.pi.ai.auth.oauth.base64UrlEncode(ByteArray(32) { 7 })

fun metadataJson(
    issuer: String,
    authorizationEndpoint: String = "$issuer/authorize",
    tokenEndpoint: String = "$issuer/token",
    registrationEndpoint: String? = null,
    scopesSupported: List<String> = listOf("mcp:read"),
    responseTypesSupported: List<String> = listOf("code"),
    codeChallengeMethodsSupported: List<String> = listOf("S256"),
    tokenEndpointAuthMethodsSupported: List<String>? = listOf("none"),
    issParameterSupported: Boolean = false,
): String = buildJsonObject {
    put("issuer", issuer)
    put("authorization_endpoint", authorizationEndpoint)
    put("token_endpoint", tokenEndpoint)
    registrationEndpoint?.let { put("registration_endpoint", it) }
    put("scopes_supported", buildJsonArray { scopesSupported.forEach { add(it) } })
    put("response_types_supported", buildJsonArray { responseTypesSupported.forEach { add(it) } })
    put("code_challenge_methods_supported", buildJsonArray { codeChallengeMethodsSupported.forEach { add(it) } })
    tokenEndpointAuthMethodsSupported?.let { methods ->
        put("token_endpoint_auth_methods_supported", buildJsonArray { methods.forEach { add(it) } })
    }
    put("authorization_response_iss_parameter_supported", issParameterSupported)
}.toString()

fun tokensJson(
    accessToken: String = "access-token",
    refreshToken: String? = "refresh-token",
    expiresIn: Long? = 3600,
    scope: String? = "mcp:read",
): String = buildJsonObject {
    put("access_token", accessToken)
    put("token_type", "Bearer")
    expiresIn?.let { put("expires_in", it) }
    scope?.let { put("scope", it) }
    refreshToken?.let { put("refresh_token", it) }
}.toString()

fun clientInformationJson(
    clientId: String = "client-1",
    clientSecret: String? = null,
    redirectUris: List<String>? = listOf("http://127.0.0.1/callback"),
): String = buildJsonObject {
    put("client_id", clientId)
    clientSecret?.let { put("client_secret", it) }
    redirectUris?.let { uris -> put("redirect_uris", buildJsonArray { uris.forEach { add(it) } }) }
    put("token_endpoint_auth_method", if (clientSecret == null) "none" else "client_secret_post")
}.toString()

fun metadataFor(issuer: String, registration: String? = null): AuthorizationServerMetadata =
    AuthorizationServerMetadata(
        issuer = issuer,
        authorizationEndpoint = "$issuer/authorize",
        tokenEndpoint = "$issuer/token",
        registrationEndpoint = registration,
        scopesSupported = listOf("mcp:read"),
        responseTypesSupported = listOf("code"),
        codeChallengeMethodsSupported = listOf("S256"),
        tokenEndpointAuthMethodsSupported = listOf("none"),
    )

fun stateWithTokens(
    serverUrl: String,
    accessToken: String = "access-token",
    refreshToken: String? = "refresh-token",
    expiresAt: Long? = null,
    client: OAuthClientInformation? = OAuthClientInformation(clientId = "client-1"),
    discovery: OAuthDiscoveryState? = OAuthDiscoveryState(
        authorizationServerUrl = "https://auth.example.com",
        authorizationServerMetadata = metadataFor("https://auth.example.com"),
    ),
    scope: String? = "mcp:read",
): McpOAuthState = McpOAuthState(
    serverUrl = serverUrl,
    clientInformation = client,
    tokens = OAuthTokens(
        accessToken = accessToken,
        tokenType = "Bearer",
        expiresIn = null,
        scope = scope,
        refreshToken = refreshToken,
    ),
    tokensExpireAt = expiresAt,
    discovery = discovery,
)

class FakeOAuthInteraction(
    private val buildCallback: (authorizationUrl: String, redirectUri: String) -> String,
) : McpOAuthInteraction {
    val authorizationUrls = mutableListOf<String>()
    val redirectUris = mutableListOf<String>()

    override suspend fun authorize(url: String, redirectUri: String): String {
        authorizationUrls += url
        redirectUris += redirectUri
        return buildCallback(url, redirectUri)
    }
}

fun callbackUrlFor(
    authorizationUrl: String,
    redirectUri: String,
    code: String = "the-code",
    overrideState: String? = null,
    issuer: String? = null,
): String {
    val params = parseUrlQuery(authorizationUrl)
    val state = overrideState ?: params["state"]
    val query = buildList {
        add("code=$code")
        state?.let { add("state=$it") }
        issuer?.let { add("iss=$it") }
    }.joinToString("&")
    return "$redirectUri?$query"
}
