package dev.pi.mcp.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    @SerialName("client_id_metadata_document_supported")
    val clientIdMetadataDocumentSupported: Boolean = false,
    @SerialName("authorization_response_iss_parameter_supported")
    val authorizationResponseIssParameterSupported: Boolean = false,
)

@Serializable
data class OAuthProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
)

@Serializable
data class OAuthClientMetadata(
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    @SerialName("application_type") val applicationType: String? = null,
    @SerialName("client_name") val clientName: String? = null,
    val scope: String? = null,
)

@Serializable
data class OAuthClientInformation(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long? = null,
    @SerialName("redirect_uris") val redirectUris: List<String>? = null,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
)

@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
)

@Serializable
data class OAuthDiscoveryState(
    val authorizationServerUrl: String,
    val authorizationServerMetadata: AuthorizationServerMetadata? = null,
    val resourceMetadata: OAuthProtectedResourceMetadata? = null,
    val resourceMetadataUrl: String? = null,
)

@Serializable
data class McpOAuthState(
    val serverUrl: String,
    val clientInformation: OAuthClientInformation? = null,
    val tokens: OAuthTokens? = null,
    val tokensExpireAt: Long? = null,
    val codeVerifier: String? = null,
    val oauthState: String? = null,
    val discovery: OAuthDiscoveryState? = null,
)

data class OAuthChallenge(
    val realm: String? = null,
    val scope: String? = null,
    val resource: String? = null,
    val resourceMetadataUrl: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
)

object WwwAuthenticate {
    fun parse(header: String?): OAuthChallenge {
        if (header.isNullOrBlank()) return OAuthChallenge()
        val trimmed = header.trimStart()
        val scheme = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase()
        if (scheme != "bearer" && scheme != "dpop") return OAuthChallenge()
        return OAuthChallenge(
            realm = parameter(header, "realm"),
            scope = parameter(header, "scope"),
            resource = parameter(header, "resource"),
            resourceMetadataUrl = parameter(header, "resource_metadata"),
            error = parameter(header, "error"),
            errorDescription = parameter(header, "error_description"),
        )
    }

    private val parameterPattern = Regex("(?:^|[,\\s])([A-Za-z_][A-Za-z0-9_]*)=")

    private fun parameter(header: String, name: String): String? {
        var searchFrom = 0
        while (true) {
            val match = parameterPattern.find(header, searchFrom) ?: return null
            if (!match.groupValues[1].equals(name, ignoreCase = true)) {
                searchFrom = match.range.last + 1
                continue
            }
            var index = match.range.last + 1
            if (index >= header.length) return null
            if (header[index] == '"') {
                val end = header.indexOf('"', index + 1)
                if (end < 0) return null
                return header.substring(index + 1, end).ifEmpty { null }
            }
            val end = header.indexOfAny(charArrayOf(',', ' ', '\t'), index)
            val value = if (end < 0) header.substring(index) else header.substring(index, end)
            return value.ifEmpty { null }
        }
    }
}

open class OAuthFlowException(message: String, cause: Throwable? = null) : Exception(message, cause)

class OAuthResponseException(
    val code: String,
    message: String,
    val errorUri: String? = null,
) : OAuthFlowException(message)

class OAuthIssuerMismatchException(
    val expected: String?,
    val received: String?,
) : OAuthFlowException(
    "OAuth issuer mismatch: expected ${formatIssuer(expected)}, received ${formatIssuer(received)}",
) {
    companion object {
        private fun formatIssuer(issuer: String?): String = if (issuer == null) "none" else "\"$issuer\""
    }
}

class OAuthInsecureEndpointException(val endpoint: String) :
    OAuthFlowException("Refusing to send OAuth credentials to non-HTTPS endpoint $endpoint")

class OAuthRegistrationException(val status: Int, val body: String) :
    OAuthFlowException("OAuth dynamic client registration failed with status $status: $body")

class OAuthDiscoveryException(message: String, cause: Throwable? = null) : OAuthFlowException(message, cause)

internal fun parseAuthorizationServerMetadata(element: JsonElement): AuthorizationServerMetadata {
    val input = requireObject(element, "authorization server metadata")
    val issuer = requiredUrl(input["issuer"], "authorization server issuer")
    val authorizationEndpoint = requiredUrl(input["authorization_endpoint"], "authorization endpoint")
    val tokenEndpoint = requiredUrl(input["token_endpoint"], "token endpoint")
    val responseTypes = optionalStrings(input["response_types_supported"], "response_types_supported")
        ?: throw OAuthDiscoveryException("Invalid response_types_supported")
    return AuthorizationServerMetadata(
        issuer = issuer,
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        registrationEndpoint = optionalUrl(input["registration_endpoint"], "registration endpoint"),
        scopesSupported = optionalStrings(input["scopes_supported"], "scopes_supported"),
        responseTypesSupported = responseTypes,
        grantTypesSupported = optionalStrings(input["grant_types_supported"], "grant_types_supported"),
        tokenEndpointAuthMethodsSupported = optionalStrings(
            input["token_endpoint_auth_methods_supported"],
            "token_endpoint_auth_methods_supported",
        ),
        codeChallengeMethodsSupported = optionalStrings(
            input["code_challenge_methods_supported"],
            "code_challenge_methods_supported",
        ),
        clientIdMetadataDocumentSupported =
            (input["client_id_metadata_document_supported"] as? JsonPrimitive)?.booleanOrNull
                ?: false,
        authorizationResponseIssParameterSupported =
            (input["authorization_response_iss_parameter_supported"] as? JsonPrimitive)?.booleanOrNull
                ?: false,
    )
}

internal fun parseProtectedResourceMetadata(element: JsonElement): OAuthProtectedResourceMetadata {
    val input = requireObject(element, "OAuth protected resource metadata")
    return OAuthProtectedResourceMetadata(
        resource = requiredUrl(input["resource"], "OAuth protected resource metadata resource"),
        authorizationServers = optionalStrings(input["authorization_servers"], "authorization_servers")
            ?.map { requiredUrl(JsonPrimitive(it), "authorization server URL") },
        scopesSupported = optionalStrings(input["scopes_supported"], "scopes_supported"),
    )
}

internal fun parseOAuthTokens(element: JsonElement): OAuthTokens {
    val input = requireObject(element, "OAuth token response")
    val expiresValue = input["expires_in"]
    val expires = if (expiresValue == null || expiresValue is JsonNull) {
        null
    } else {
        val primitive = expiresValue as? JsonPrimitive ?: throw OAuthFlowException("Invalid expires_in")
        primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: throw OAuthFlowException("Invalid expires_in")
    }
    return OAuthTokens(
        accessToken = requiredString(input["access_token"], "access_token"),
        tokenType = requiredString(input["token_type"], "token_type"),
        expiresIn = expires,
        scope = optionalString(input["scope"], "scope"),
        refreshToken = optionalString(input["refresh_token"], "refresh_token"),
        idToken = optionalString(input["id_token"], "id_token"),
    )
}

internal fun parseClientInformation(element: JsonElement): OAuthClientInformation {
    val input = requireObject(element, "OAuth client registration response")
    return OAuthClientInformation(
        clientId = requiredString(input["client_id"], "client_id"),
        clientSecret = optionalString(input["client_secret"], "client_secret"),
        clientIdIssuedAt = (input["client_id_issued_at"] as? JsonPrimitive)?.longOrNull,
        clientSecretExpiresAt = (input["client_secret_expires_at"] as? JsonPrimitive)?.longOrNull,
        redirectUris = optionalStrings(input["redirect_uris"], "redirect_uris"),
        tokenEndpointAuthMethod = optionalString(input["token_endpoint_auth_method"], "token_endpoint_auth_method"),
    )
}

internal fun requireObject(value: JsonElement?, name: String): JsonObject =
    value as? JsonObject ?: throw OAuthFlowException("Invalid $name")

private fun requiredString(value: JsonElement?, name: String): String {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text.isNullOrEmpty()) throw OAuthFlowException("Invalid $name")
    return text
}

private fun optionalString(value: JsonElement?, name: String): String? {
    if (isAbsent(value)) return null
    return requiredString(value, name)
}

private fun optionalStrings(value: JsonElement?, name: String): List<String>? {
    if (isAbsent(value)) return null
    val array = value as? JsonArray ?: throw OAuthFlowException("Invalid $name")
    return array.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw OAuthFlowException("Invalid $name")
    }
}

private fun isAbsent(value: JsonElement?): Boolean =
    value == null || value is JsonPrimitive && value.contentOrNull.let { it == null || it.isEmpty() }

private fun requiredUrl(value: JsonElement?, name: String): String = safeUrl(requiredString(value, name), name)

private fun optionalUrl(value: JsonElement?, name: String): String? {
    if (isAbsent(value)) return null
    return safeUrl(requiredString(value, name), name)
}

private fun safeUrl(value: String, name: String): String {
    if (!value.contains("://")) throw OAuthFlowException("Invalid $name")
    val scheme = value.substringBefore("://").lowercase()
    if (scheme == "javascript" || scheme == "data" || scheme == "vbscript") {
        throw OAuthFlowException("Invalid $name")
    }
    if (scheme.isEmpty() || value.substringAfter("://").isEmpty()) throw OAuthFlowException("Invalid $name")
    return value
}
