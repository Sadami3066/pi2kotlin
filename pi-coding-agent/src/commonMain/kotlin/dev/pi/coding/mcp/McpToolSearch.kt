package dev.pi.coding.mcp

import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.textResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.ln

data class McpDeferredTool(
    val name: String,
    val description: String?,
    val server: String,
    val parameters: kotlinx.serialization.json.JsonObject,
)

private const val TOOL_SEARCH_NAME = "tool_search"
private const val DEFAULT_TOOL_SEARCH_LIMIT = 8
private const val BM25_K1 = 1.2
private const val BM25_B = 0.75

private const val TOOL_SEARCH_DESCRIPTION = "# Tool discovery\n\n" +
    "Searches over deferred tool metadata with BM25 and exposes matching tools for the next model call.\n\n" +
    "Some of the tools, such as tools of MCP servers, may not have been provided to you upfront, and you should use " +
    "this tool (`$TOOL_SEARCH_NAME`) to search for the required tools. For MCP tool discovery, always use " +
    "`$TOOL_SEARCH_NAME`."

private val STOP_WORDS: Set<String> = setOf(
    "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "that",
    "the", "this", "to", "with",
)

private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

private fun isCjk(char: Char): Boolean = when (char.code) {
    in 0x3040..0x30FF -> true
    in 0x3400..0x4DBF -> true
    in 0x4E00..0x9FFF -> true
    in 0xAC00..0xD7AF -> true
    in 0xF900..0xFAFF -> true
    else -> false
}

internal fun tokenizeToolSearchText(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val ascii = StringBuilder()
    val cjkRun = StringBuilder()

    fun flushAscii() {
        if (ascii.isEmpty()) return
        val term = ascii.toString().lowercase()
        ascii.setLength(0)
        if (term.isNotEmpty() && term !in STOP_WORDS) {
            tokens += stemToolSearchTerm(term)
        }
    }

    fun flushCjk() {
        if (cjkRun.isEmpty()) return
        val run = cjkRun.toString()
        cjkRun.setLength(0)
        if (run.length == 1) {
            tokens += run
        } else {
            for (index in 0 until run.length - 1) {
                tokens += run.substring(index, index + 2)
            }
        }
    }

    for (char in text.replace(CAMEL_BOUNDARY, "$1 $2")) {
        when {
            isCjk(char) -> {
                flushAscii()
                cjkRun.append(char)
            }
            char.code < 128 && char.isLetterOrDigit() -> {
                flushCjk()
                ascii.append(char)
            }
            else -> {
                flushAscii()
                flushCjk()
            }
        }
    }
    flushAscii()
    flushCjk()
    return tokens
}

private fun stemToolSearchTerm(term: String): String {
    if (term.length > 4 && term.endsWith("ies")) return term.dropLast(3) + "y"
    if (term.length > 4 && Regex("(ches|shes|sses|xes|zes)$").containsMatchIn(term)) return term.dropLast(2)
    if (term.length > 3 && term.endsWith("s") && !term.endsWith("ss")) return term.dropLast(1)
    return term
}

private fun schemaText(schema: JsonElement?, parts: MutableList<String>) {
    val obj = schema as? kotlinx.serialization.json.JsonObject ?: return
    (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { parts += it }
    (obj["properties"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, property) ->
        parts += name
        schemaText(property, parts)
    }
    schemaText(obj["items"], parts)
    for (key in listOf("anyOf", "oneOf", "allOf")) {
        (obj[key] as? JsonArray)?.forEach { variant -> schemaText(variant, parts) }
    }
}

internal fun toolSearchText(tool: McpDeferredTool): String {
    val parts = mutableListOf(tool.name, tool.name.replace('_', ' '))
    tool.description?.let { parts += it }
    schemaText(tool.parameters, parts)
    return parts.filter { it.isNotBlank() }.joinToString(" ")
}

class McpToolSearch(
    private val catalog: () -> List<McpDeferredTool>,
    private val onLoaded: suspend (List<String>) -> Unit,
) {
    fun search(query: String, limit: Int = DEFAULT_TOOL_SEARCH_LIMIT): List<McpDeferredTool> {
        val queryTerms = tokenizeToolSearchText(query).distinct()
        if (queryTerms.isEmpty() || limit <= 0) return emptyList()
        val documents = catalog()
        if (documents.isEmpty()) return emptyList()
        val termCounts = documents.map { tool ->
            val counts = HashMap<String, Int>()
            for (term in tokenizeToolSearchText(toolSearchText(tool))) {
                counts[term] = (counts[term] ?: 0) + 1
            }
            counts
        }
        val lengths = termCounts.map { counts -> counts.values.sum() }
        val totalLength = lengths.sum()
        val averageLength = if (documents.isEmpty()) 1.0 else {
            if (totalLength == 0) 1.0 else totalLength.toDouble() / documents.size
        }
        val idf = queryTerms.associateWith { term ->
            val frequency = termCounts.count { it.containsKey(term) }
            ln(1 + (documents.size - frequency + 0.5) / (frequency + 0.5))
        }
        val scored = mutableListOf<Triple<McpDeferredTool, Double, String>>()
        documents.forEachIndexed { index, tool ->
            var score = 0.0
            for (term in queryTerms) {
                val count = termCounts[index][term] ?: continue
                val normalization = BM25_K1 * (1 - BM25_B + BM25_B * lengths[index] / averageLength)
                score += (idf[term] ?: 0.0) * (count * (BM25_K1 + 1)) / (count + normalization)
            }
            if (score > 0.0) {
                scored += Triple(tool, score, tool.name)
            }
        }
        return scored
            .sortedWith(compareByDescending<Triple<McpDeferredTool, Double, String>> { it.second }.thenBy { it.third })
            .take(limit)
            .map { it.first }
    }

    fun toCodingTool(): CodingTool = CodingTool(
        name = TOOL_SEARCH_NAME,
        description = TOOL_SEARCH_DESCRIPTION,
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Search query for deferred tools.")
                        },
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "number")
                            put(
                                "description",
                                "Maximum number of tools to return. Defaults to $DEFAULT_TOOL_SEARCH_LIMIT.",
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        },
        label = TOOL_SEARCH_NAME,
        promptSnippet = "Search deferred tools and load matches for the next model call",
        promptGuidelines = null,
        execute = { _toolCallId, args, _signal, _onUpdate ->
            val query = (args["query"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (query.isBlank()) {
                throw IllegalArgumentException("query must not be empty")
            }
            val requested = (args["limit"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
            val limit = requested ?: DEFAULT_TOOL_SEARCH_LIMIT
            if (limit <= 0) {
                throw IllegalArgumentException("limit must be a positive integer")
            }
            val loaded = search(query, limit)
            val names = loaded.map { it.name }
            if (names.isEmpty()) {
                textResult("No matching tools found.", buildJsonObject { put("loaded", buildJsonArray {}) })
            } else {
                onLoaded(names)
                val lines = loaded.joinToString("\n") { tool ->
                    "- ${tool.name}: ${firstDescriptionLine(tool.description)}"
                }
                val text = "Loaded ${loaded.size} tool${if (loaded.size == 1) "" else "s"}. " +
                    "They are available from your next call:\n$lines"
                textResult(
                    text,
                    buildJsonObject {
                        put("loaded", buildJsonArray { names.forEach { name -> add(JsonPrimitive(name)) } })
                    },
                )
            }
        },
    )
}

private fun firstDescriptionLine(description: String?): String {
    val value = description?.trim().orEmpty()
    if (value.isEmpty()) return ""
    return value.lineSequence().first()
}
