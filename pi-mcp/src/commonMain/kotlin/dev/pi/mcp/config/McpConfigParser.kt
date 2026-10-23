package dev.pi.mcp.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil

data class McpConfigParseResult(
    val servers: List<Pair<String, McpServerConfig>>,
    val errors: List<String>,
)

data class McpServerOverrideResult(
    val config: McpServerConfig?,
    val errors: List<String>,
)

class McpConfigException(
    val errors: List<String>,
) : IllegalArgumentException(errors.joinToString("; "))

private val SERVER_NAME = Regex("^[A-Za-z0-9_-]+$")
private val EXPOSURE_VALUES = setOf("direct", "deferred", "codemode", "hidden")
private val EXPOSURE_LIST = "\"direct\", \"deferred\", \"codemode\", \"hidden\""
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
private val OVERRIDE_KEYS = setOf("enabled", "exposure", "toolExposure")

fun parseMcpServers(root: JsonObject): List<Pair<String, McpServerConfig>> {
    val result = parseMcpConfig(root)
    if (result.errors.isNotEmpty()) throw McpConfigException(result.errors)
    return result.servers
}

fun parseMcpConfig(root: JsonObject): McpConfigParseResult {
    val rootServers = root["mcpServers"]
        ?: return McpConfigParseResult(emptyList(), emptyList())
    if (rootServers !is JsonObject) {
        return McpConfigParseResult(emptyList(), listOf("mcpServers must be an object"))
    }
    val errors = mutableListOf<String>()
    val servers = LinkedHashMap<String, McpServerConfig>()
    val namespaces = LinkedHashMap<String, String>()
    for ((name, value) in rootServers) {
        if (!SERVER_NAME.matches(name)) {
            errors.add("mcpServers.$name: invalid server name (use letters, digits, \"_\" and \"-\")")
            continue
        }
        val namespace = name.replace('-', '_')
        val clash = namespaces.entries.firstOrNull { it.value == namespace }
        if (clash != null) {
            errors.add("mcpServers.$name: server name conflicts with \"${clash.key}\"")
            continue
        }
        val config = parseServer(name, value, errors)
        if (config != null) {
            servers[name] = config
            namespaces[name] = namespace
        }
    }
    return McpConfigParseResult(servers.map { it.key to it.value }, errors)
}

fun applyMcpServerOverrides(base: McpServerConfig, override: JsonObject): McpServerOverrideResult {
    val errors = mutableListOf<String>()
    for (key in override.keys) {
        if (key !in OVERRIDE_KEYS) {
            errors.add(
                "override.$key: project mcp.json entries may only override enabled, exposure, toolExposure",
            )
        }
    }
    var enabled: Boolean? = null
    var exposure: McpExposure? = null
    var toolExposure: Map<String, McpExposure>? = null
    override["enabled"]?.let { enabled = parseBoolean(it, "override.enabled", errors) }
    override["exposure"]?.let { exposure = parseExposure(it, "override.exposure", errors) }
    override["toolExposure"]?.let { toolExposure = parseToolExposure(it, "override.toolExposure", errors) }
    if (errors.isNotEmpty()) return McpServerOverrideResult(null, errors)
    val merged = when (base) {
        is McpServerConfig.Stdio -> base.copy(
            enabled = enabled ?: base.enabled,
            exposure = exposure ?: base.exposure,
            toolExposure = toolExposure ?: base.toolExposure,
        )

        is McpServerConfig.Http -> base.copy(
            enabled = enabled ?: base.enabled,
            exposure = exposure ?: base.exposure,
            toolExposure = toolExposure ?: base.toolExposure,
        )
    }
    return McpServerOverrideResult(merged, emptyList())
}

private fun parseServer(name: String, value: JsonElement, errors: MutableList<String>): McpServerConfig? {
    val path = "mcpServers.$name"
    if (value !is JsonObject) {
        errors.add("$path: server must be an object")
        return null
    }
    val entryErrors = mutableListOf<String>()
    var type: String? = null
    val typeElement = value["type"]
    if (typeElement != null) {
        if (typeElement !is JsonPrimitive || !typeElement.isString) {
            entryErrors.add("$path.type: must be a string")
        } else {
            type = typeElement.content
            if (type != "stdio" && type != "http" && type != "streamable-http" && type != "sse") {
                entryErrors.add("$path.type: must be \"stdio\" or \"http\"")
            }
        }
    }
    if (type == "sse") {
        entryErrors.add("$path.type: legacy SSE transport is not supported; use the streamable HTTP URL")
    }
    val exposure = if (value.containsKey("exposure")) {
        parseExposure(value["exposure"]!!, "$path.exposure", entryErrors)
    } else {
        McpExposure.Deferred
    }
    val toolExposure = if (value.containsKey("toolExposure")) {
        parseToolExposure(value["toolExposure"]!!, "$path.toolExposure", entryErrors)
    } else {
        emptyMap()
    }
    val enabled = if (value.containsKey("enabled")) {
        parseBoolean(value["enabled"]!!, "$path.enabled", entryErrors) ?: true
    } else {
        true
    }
    val description = if (value.containsKey("description")) {
        parseString(value["description"]!!, "$path.description", entryErrors)
    } else {
        null
    }
    val timeoutSeconds = if (value.containsKey("timeout")) {
        parseTimeout(value["timeout"]!!, "$path.timeout", entryErrors) ?: 60L
    } else {
        60L
    }
    val urlElement = value["url"]
    val commandElement = value["command"]
    val urlIsString = urlElement is JsonPrimitive && urlElement.isString
    val commandIsString = commandElement is JsonPrimitive && commandElement.isString
    val effectiveType = type
    val effectiveExposure = exposure ?: McpExposure.Deferred
    val effectiveToolExposure = toolExposure ?: emptyMap()
    val config = when {
        urlIsString && (effectiveType == null || effectiveType == "http" || effectiveType == "streamable-http") ->
            parseHttp(
                value,
                path,
                entryErrors,
                enabled,
                timeoutSeconds,
                description,
                effectiveExposure,
                effectiveToolExposure,
            )

        commandIsString && (effectiveType == null || effectiveType == "stdio") ->
            parseStdio(
                value,
                path,
                entryErrors,
                enabled,
                timeoutSeconds,
                description,
                effectiveExposure,
                effectiveToolExposure,
            )

        effectiveType == "sse" -> null
        else -> {
            if (effectiveType == null || effectiveType == "stdio" || effectiveType == "http" ||
                effectiveType == "streamable-http"
            ) {
                entryErrors.add("$path: needs either \"command\" (stdio) or \"url\" (streamable HTTP)")
            }
            null
        }
    }
    if (entryErrors.isNotEmpty() || config == null) {
        errors.addAll(entryErrors)
        return null
    }
    return config
}

private fun parseStdio(
    value: JsonObject,
    path: String,
    errors: MutableList<String>,
    enabled: Boolean,
    timeoutSeconds: Long,
    description: String?,
    exposure: McpExposure,
    toolExposure: Map<String, McpExposure>,
): McpServerConfig.Stdio? {
    val command = (value["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (command == null) {
        errors.add("$path.command: must be a string")
        return null
    }
    val args = if (value.containsKey("args")) {
        val element = value["args"]!!
        val array = element as? JsonArray
        if (array == null || array.any { it !is JsonPrimitive || !it.isString }) {
            errors.add("$path.args: must be an array of strings")
            null
        } else {
            array.map { (it as JsonPrimitive).content }
        }
    } else {
        emptyList()
    }
    val env = if (value.containsKey("env")) {
        parseStringMap(value["env"]!!, "$path.env", errors)
    } else {
        emptyMap()
    }
    val cwd = if (value.containsKey("cwd")) {
        parseString(value["cwd"]!!, "$path.cwd", errors)
    } else {
        null
    }
    if (errors.isNotEmpty()) return null
    return McpServerConfig.Stdio(
        command = command,
        args = args ?: emptyList(),
        env = env ?: emptyMap(),
        cwd = cwd,
        enabled = enabled,
        timeoutSeconds = timeoutSeconds,
        description = description,
        exposure = exposure,
        toolExposure = toolExposure,
    )
}

private fun parseHttp(
    value: JsonObject,
    path: String,
    errors: MutableList<String>,
    enabled: Boolean,
    timeoutSeconds: Long,
    description: String?,
    exposure: McpExposure,
    toolExposure: Map<String, McpExposure>,
): McpServerConfig.Http? {
    val url = (value["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (url == null) {
        errors.add("$path.url: must be a string")
        return null
    }
    val parsedUrl = parseUri(url)
    if (parsedUrl == null || (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") || parsedUrl.host.isEmpty()) {
        errors.add("$path.url: must be an http or https URL")
    }
    val headers = if (value.containsKey("headers")) {
        parseStringMap(value["headers"]!!, "$path.headers", errors)
    } else {
        emptyMap()
    }
    val oauth = if (value.containsKey("oauth")) {
        parseOAuth(value["oauth"]!!, "$path.oauth", errors)
    } else {
        null
    }
    var authProvider: String? = null
    if (value.containsKey("auth")) {
        val auth = value["auth"]
        if (auth !is JsonObject) {
            errors.add("$path.auth: auth must be an object")
        } else {
            val provider = auth["provider"]
            if (provider !is JsonPrimitive || !provider.isString || provider.content.isBlank()) {
                errors.add("$path.auth.provider: auth.provider must be a provider name")
            } else {
                authProvider = provider.content
                val target = parsedUrl
                if (target != null && target.scheme != "https" && !isLoopbackHost(target.host)) {
                    errors.add(
                        "$path.auth: auth requires an https URL, or http on localhost, 127.0.0.1, or [::1]",
                    )
                }
            }
        }
    }
    if (errors.isNotEmpty()) return null
    return McpServerConfig.Http(
        url = url,
        headers = headers ?: emptyMap(),
        oauth = oauth,
        authProvider = authProvider,
        enabled = enabled,
        timeoutSeconds = timeoutSeconds,
        description = description,
        exposure = exposure,
        toolExposure = toolExposure,
    )
}

private fun parseOAuth(value: JsonElement, path: String, errors: MutableList<String>): McpOAuthConfig? {
    if (value !is JsonObject) {
        errors.add("$path: oauth must be an object")
        return null
    }
    val clientId = parseOptionalString(value, "clientId", path, errors)
    val clientSecret = parseOptionalString(value, "clientSecret", path, errors)
    val callbackPort = if (value.containsKey("callbackPort")) {
        parseCallbackPort(value["callbackPort"]!!, "$path.callbackPort", errors)
    } else {
        null
    }
    var callbackUrlPort: Int? = null
    var callbackHost: String? = null
    var callbackPath: String? = null
    val callbackUrl = if (value.containsKey("callbackUrl")) {
        val element = value["callbackUrl"]
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null) {
            errors.add("$path.callbackUrl: must be a string")
            null
        } else {
            val parsed = parseUri(text)
            if (parsed == null || parsed.scheme != "http" || !isLoopbackHost(parsed.host) ||
                parsed.query.isNotEmpty() || parsed.fragment.isNotEmpty()
            ) {
                errors.add(
                    "$path.callbackUrl: must be an http URI on localhost, 127.0.0.1, or [::1] without query or fragment",
                )
                null
            } else {
                callbackUrlPort = parsed.port
                callbackHost = parsed.host
                callbackPath = parsed.path
                text
            }
        }
    } else {
        null
    }
    if (callbackUrlPort != null && callbackPort != null && callbackUrlPort != callbackPort) {
        errors.add("$path: oauth.callbackUrl and oauth.callbackPort name different ports")
    }
    val scope = parseOptionalString(value, "scope", path, errors)
    val clientName = parseOptionalString(value, "clientName", path, errors)
    if (clientName != null && clientName.isBlank()) {
        errors.add("$path.clientName: oauth.clientName must be a non-empty string")
    }
    var clientRegistration: String? = null
    if (value.containsKey("clientRegistration")) {
        val element = value["clientRegistration"]
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text != "dcr" && text != "cimd") {
            errors.add("$path.clientRegistration: oauth.clientRegistration must be \"dcr\" or \"cimd\"")
        } else {
            clientRegistration = text
        }
    }
    if (clientRegistration == "cimd") {
        if (clientId != null || clientName != null) {
            errors.add(
                "$path.clientRegistration: oauth.clientRegistration \"cimd\" cannot be combined with oauth.clientId or oauth.clientName",
            )
        }
        if (callbackHost != null && (callbackHost == "[::1]" || callbackPath != "/callback")) {
            errors.add(
                "$path.callbackUrl: oauth.clientRegistration \"cimd\" requires oauth.callbackUrl on localhost or 127.0.0.1 with path /callback",
            )
        }
    }
    var metadataUrl: String? = null
    if (value.containsKey("authServerMetadataUrl")) {
        val element = value["authServerMetadataUrl"]
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        val parsed = text?.let(::parseUri)
        if (text == null || parsed == null ||
            (parsed.scheme != "https" && !(parsed.scheme == "http" && isLoopbackHost(parsed.host)))
        ) {
            errors.add(
                "$path.authServerMetadataUrl: oauth.authServerMetadataUrl must be an https URL, or http on localhost, 127.0.0.1, or [::1]",
            )
        } else {
            metadataUrl = text
        }
    }
    return McpOAuthConfig(
        clientId = clientId,
        clientSecret = clientSecret,
        callbackPort = callbackPort,
        callbackUrl = callbackUrl,
        scope = scope,
        clientName = clientName,
        clientRegistration = clientRegistration,
        authServerMetadataUrl = metadataUrl,
    )
}

private fun parseCallbackPort(value: JsonElement, path: String, errors: MutableList<String>): Int? {
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        errors.add("$path: oauth.callbackPort must be a port number")
        return null
    }
    val port = primitive.content.toIntOrNull()
    if (port == null || port < 1 || port > 65535) {
        errors.add("$path: oauth.callbackPort must be a port number")
        return null
    }
    return port
}

private fun parseOptionalString(
    value: JsonObject,
    key: String,
    path: String,
    errors: MutableList<String>,
): String? {
    if (!value.containsKey(key)) return null
    val element = value[key]!!
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text == null) {
        errors.add("$path.$key: oauth.$key must be a string")
    }
    return text
}

private fun parseExposure(value: JsonElement, path: String, errors: MutableList<String>): McpExposure? {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text == null) {
        errors.add("$path: exposure must be one of $EXPOSURE_LIST")
        return null
    }
    return try {
        McpExposure.parse(text)
    } catch (error: IllegalArgumentException) {
        errors.add("$path: exposure must be one of $EXPOSURE_LIST")
        null
    }
}

private fun parseToolExposure(
    value: JsonElement,
    path: String,
    errors: MutableList<String>,
): Map<String, McpExposure>? {
    if (value !is JsonObject) {
        errors.add("$path: toolExposure must map tool names to exposures")
        return null
    }
    val result = LinkedHashMap<String, McpExposure>()
    for ((tool, exposureElement) in value) {
        if (tool.isEmpty()) {
            errors.add("$path: toolExposure keys must be non-empty tool names or patterns")
            continue
        }
        val exposure = parseExposure(exposureElement, "$path.$tool", errors) ?: continue
        result[tool] = exposure
    }
    return result
}

private fun parseBoolean(value: JsonElement, path: String, errors: MutableList<String>): Boolean? {
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        errors.add("$path: must be a boolean")
        return null
    }
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> {
            errors.add("$path: must be a boolean")
            null
        }
    }
}

private fun parseString(value: JsonElement, path: String, errors: MutableList<String>): String? {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text == null) errors.add("$path: must be a string")
    return text
}

private fun parseStringMap(
    value: JsonElement,
    path: String,
    errors: MutableList<String>,
): Map<String, String>? {
    if (value !is JsonObject) {
        errors.add("$path: must map names to strings")
        return null
    }
    val result = LinkedHashMap<String, String>()
    for ((key, element) in value) {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null) {
            errors.add("$path: must map names to strings")
            return null
        }
        result[key] = text
    }
    return result
}

private fun parseTimeout(value: JsonElement, path: String, errors: MutableList<String>): Long? {
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString) {
        errors.add("$path: timeout must be a positive number of seconds")
        return null
    }
    val seconds = primitive.content.toDoubleOrNull()
    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) {
        errors.add("$path: timeout must be a positive number of seconds")
        return null
    }
    return ceil(seconds).toLong()
}

private data class ParsedUri(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String,
    val fragment: String,
)

private fun parseUri(raw: String): ParsedUri? {
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme.isEmpty() || !scheme[0].isLetter()) return null
    if (scheme.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
    val rest = raw.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
    if (authority.isEmpty()) return null
    val afterAuthority = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
    val hostPort = if (authority.contains('@')) authority.substringAfterLast('@') else authority
    var host: String
    var port: Int? = null
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        if (close < 0) return null
        host = hostPort.substring(0, close + 1)
        val remainder = hostPort.substring(close + 1)
        if (remainder.startsWith(":")) {
            port = remainder.substring(1).toIntOrNull() ?: return null
        } else if (remainder.isNotEmpty()) {
            return null
        }
    } else {
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0) {
            if (hostPort.indexOf(':') != colon) return null
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = hostPort
        }
    }
    if (host.isEmpty()) return null
    if (port != null && (port < 1 || port > 65535)) return null
    var fragment = ""
    var withoutFragment = afterAuthority
    val hashIndex = afterAuthority.indexOf('#')
    if (hashIndex >= 0) {
        fragment = afterAuthority.substring(hashIndex + 1)
        withoutFragment = afterAuthority.substring(0, hashIndex)
    }
    var query = ""
    var path = withoutFragment
    val queryIndex = withoutFragment.indexOf('?')
    if (queryIndex >= 0) {
        query = withoutFragment.substring(queryIndex + 1)
        path = withoutFragment.substring(0, queryIndex)
    }
    return ParsedUri(
        scheme = scheme,
        host = host.lowercase(),
        port = port,
        path = path,
        query = query,
        fragment = fragment,
    )
}

private fun isLoopbackHost(host: String): Boolean = host.lowercase() in LOOPBACK_HOSTS
