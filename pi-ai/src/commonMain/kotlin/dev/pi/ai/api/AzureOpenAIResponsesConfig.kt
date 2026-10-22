package dev.pi.ai.api

import dev.pi.ai.Model

internal const val DEFAULT_AZURE_API_VERSION = "v1"

internal const val AZURE_BASE_URL_ENV = "AZURE_OPENAI_BASE_URL"
internal const val AZURE_RESOURCE_NAME_ENV = "AZURE_OPENAI_RESOURCE_NAME"
internal const val AZURE_API_VERSION_ENV = "AZURE_OPENAI_API_VERSION"
internal const val AZURE_DEPLOYMENT_NAME_MAP_ENV = "AZURE_OPENAI_DEPLOYMENT_NAME_MAP"

internal const val AZURE_BASE_URL_REQUIRED_MESSAGE =
    "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME, " +
        "or pass azureBaseUrl, azureResourceName, or model.baseUrl."

internal class AzureResolvedConfig(
    val baseUrl: String,
    val apiVersion: String,
)

internal fun parseAzureDeploymentNameMap(value: String?): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    if (value.isNullOrEmpty()) return map
    for (entry in value.split(",")) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val separator = trimmed.indexOf('=')
        if (separator <= 0 || separator == trimmed.length - 1) continue
        val modelId = trimmed.substring(0, separator).trim()
        val deploymentName = trimmed.substring(separator + 1).trim()
        if (modelId.isEmpty() || deploymentName.isEmpty()) continue
        map[modelId] = deploymentName
    }
    return map
}

internal fun resolveAzureDeploymentName(model: Model, env: Map<String, String>?): String {
    val mapped = parseAzureDeploymentNameMap(env?.get(AZURE_DEPLOYMENT_NAME_MAP_ENV)).get(model.id)
    return if (mapped.isNullOrEmpty()) model.id else mapped
}

internal fun resolveAzureBaseUrl(model: Model, env: Map<String, String>?): String {
    val envBaseUrl = env?.get(AZURE_BASE_URL_ENV)?.trim().orEmpty()
    val resourceName = env?.get(AZURE_RESOURCE_NAME_ENV)?.trim().orEmpty()
    var resolvedBaseUrl = envBaseUrl.takeIf { it.isNotEmpty() }
    if (resolvedBaseUrl == null && resourceName.isNotEmpty()) {
        resolvedBaseUrl = "https://$resourceName.openai.azure.com/openai/v1"
    }
    if (resolvedBaseUrl == null) {
        val modelBaseUrl = model.baseUrl.trim()
        if (modelBaseUrl.isNotEmpty()) resolvedBaseUrl = modelBaseUrl
    }
    val baseUrl = resolvedBaseUrl ?: throw IllegalArgumentException(AZURE_BASE_URL_REQUIRED_MESSAGE)
    return normalizeAzureBaseUrl(baseUrl)
}

internal fun resolveAzureConfig(model: Model, env: Map<String, String>?): AzureResolvedConfig = AzureResolvedConfig(
    baseUrl = resolveAzureBaseUrl(model, env),
    apiVersion = resolveAzureApiVersion(env),
)

internal fun resolveAzureApiVersion(env: Map<String, String>?): String {
    val fromEnv = env?.get(AZURE_API_VERSION_ENV)?.trim().orEmpty()
    val apiVersion = if (fromEnv.isNotEmpty()) fromEnv else DEFAULT_AZURE_API_VERSION
    validateAzureApiVersion(apiVersion)
    return apiVersion
}

internal fun validateAzureApiVersion(apiVersion: String) {
    val valid = apiVersion.isNotEmpty() && apiVersion.all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.' || char == '_' || char == '-'
    }
    if (!valid) throw IllegalArgumentException("Invalid Azure OpenAI API version: $apiVersion")
}

internal fun normalizeAzureBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val parsed = parseAzureUrl(trimmed) ?: throw IllegalArgumentException("Invalid Azure OpenAI base URL: $baseUrl")
    val isAzureHost = parsed.host.endsWith(".openai.azure.com") ||
        parsed.host.endsWith(".cognitiveservices.azure.com") ||
        parsed.host.endsWith(".ai.azure.com")
    val normalizedPath = parsed.path.trimEnd('/')
    val shouldNormalizePath = isAzureHost && (
        normalizedPath.isEmpty() ||
            normalizedPath == "/openai" ||
            normalizedPath == "/openai/v1/responses"
        )
    val path = if (shouldNormalizePath) "/openai/v1" else normalizedPath
    val query = if (shouldNormalizePath) "" else parsed.query
    val authority = if (parsed.port == null) parsed.host else "${parsed.host}:${parsed.port}"
    val builder = StringBuilder()
    builder.append(parsed.scheme).append("://").append(authority)
    if (path.isNotEmpty()) builder.append(path)
    if (query.isNotEmpty()) builder.append('?').append(query)
    return builder.toString()
}

internal fun buildAzureResponsesUrl(baseUrl: String, apiVersion: String): String {
    val path = if (baseUrl.endsWith("/responses")) baseUrl else "$baseUrl/responses"
    val separator = if (path.contains('?')) '&' else '?'
    return "$path${separator}api-version=$apiVersion"
}

internal class AzureParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String,
)

internal fun parseAzureUrl(value: String): AzureParsedUrl? {
    val schemeSeparator = value.indexOf("://")
    if (schemeSeparator <= 0) return null
    val scheme = value.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val remainder = value.substring(schemeSeparator + 3)
    if (remainder.isEmpty() || remainder.any { it.isWhitespace() }) return null
    val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd == -1) remainder else remainder.substring(0, authorityEnd)
    if (authority.isEmpty()) return null
    var pathAndQuery = if (authorityEnd == -1) "" else remainder.substring(authorityEnd)
    val fragmentIndex = pathAndQuery.indexOf('#')
    if (fragmentIndex != -1) pathAndQuery = pathAndQuery.substring(0, fragmentIndex)
    val queryIndex = pathAndQuery.indexOf('?')
    val path = when {
        queryIndex == -1 -> pathAndQuery
        else -> pathAndQuery.substring(0, queryIndex)
    }
    val query = if (queryIndex == -1) "" else pathAndQuery.substring(queryIndex + 1)
    if (path.isNotEmpty() && !path.startsWith("/")) return null
    var host = authority
    var port: Int? = null
    val portSeparator = authority.lastIndexOf(':')
    if (portSeparator > 0 && portSeparator < authority.length - 1) {
        val candidate = authority.substring(portSeparator + 1)
        if (candidate.all { it.isDigit() }) {
            port = candidate.toIntOrNull() ?: return null
            host = authority.substring(0, portSeparator)
        }
    }
    if (host.isEmpty()) return null
    return AzureParsedUrl(scheme, host, port, path, query)
}
