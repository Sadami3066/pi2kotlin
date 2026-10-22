package dev.pi.coding.models

internal object ConfigValues {
    private val envName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val envNamePrefix = Regex("^[A-Za-z_][A-Za-z0-9_]*")

    private sealed interface Part {
        data class Literal(val value: String) : Part

        data class Env(val name: String) : Part
    }

    fun isCommand(value: String): Boolean = value.startsWith("!")

    fun envVarNames(value: String): List<String> {
        if (isCommand(value)) return emptyList()
        return parse(value).filterIsInstance<Part.Env>().map { it.name }.distinct()
    }

    fun isConfigured(value: String, env: Map<String, String>?): Boolean {
        if (isCommand(value)) return true
        return envVarNames(value).all { name -> !env?.get(name).isNullOrEmpty() }
    }

    fun resolve(value: String, env: Map<String, String>?): String? {
        if (isCommand(value)) return null
        val builder = StringBuilder()
        for (part in parse(value)) {
            when (part) {
                is Part.Literal -> builder.append(part.value)
                is Part.Env -> {
                    val resolved = env?.get(part.name)
                    if (resolved.isNullOrEmpty()) return null
                    builder.append(resolved)
                }
            }
        }
        return builder.toString()
    }

    fun resolveHeaders(headers: Map<String, String>?, env: Map<String, String>?): Map<String, String>? {
        if (headers == null) return null
        val resolved = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val resolvedValue = resolve(value, env) ?: continue
            if (resolvedValue.isNotEmpty()) resolved[name] = resolvedValue
        }
        return resolved.ifEmpty { null }
    }

    private fun parse(config: String): List<Part> {
        val parts = mutableListOf<Part>()
        var index = 0
        while (index < config.length) {
            val dollarIndex = config.indexOf('$', index)
            if (dollarIndex < 0) {
                appendLiteral(parts, config.substring(index))
                break
            }
            appendLiteral(parts, config.substring(index, dollarIndex))
            val nextChar = config.getOrNull(dollarIndex + 1)
            when {
                nextChar == '$' || nextChar == '!' -> {
                    appendLiteral(parts, nextChar.toString())
                    index = dollarIndex + 2
                }

                nextChar == '{' -> {
                    val endIndex = config.indexOf('}', dollarIndex + 2)
                    if (endIndex < 0) {
                        appendLiteral(parts, "$")
                        index = dollarIndex + 1
                    } else {
                        val name = config.substring(dollarIndex + 2, endIndex)
                        if (envName.matches(name)) {
                            parts.add(Part.Env(name))
                        } else {
                            appendLiteral(parts, config.substring(dollarIndex, endIndex + 1))
                        }
                        index = endIndex + 1
                    }
                }

                nextChar != null -> {
                    val match = envNamePrefix.find(config.substring(dollarIndex + 1))
                    if (match != null) {
                        parts.add(Part.Env(match.value))
                        index = dollarIndex + 1 + match.value.length
                    } else {
                        appendLiteral(parts, "$")
                        index = dollarIndex + 1
                    }
                }

                else -> {
                    appendLiteral(parts, "$")
                    index = dollarIndex + 1
                }
            }
        }
        return parts
    }

    private fun appendLiteral(parts: MutableList<Part>, value: String) {
        if (value.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is Part.Literal) {
            parts[parts.size - 1] = Part.Literal(last.value + value)
            return
        }
        parts.add(Part.Literal(value))
    }
}
