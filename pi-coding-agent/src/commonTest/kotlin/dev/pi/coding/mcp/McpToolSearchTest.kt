package dev.pi.coding.mcp

import dev.pi.agent.AgentToolResult
import dev.pi.ai.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolSearchTest {
    private fun toolSchema(vararg propertyNames: String): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                for (name in propertyNames) {
                    put(
                        name,
                        buildJsonObject {
                            put("type", "string")
                            put("description", "The $name field")
                        },
                    )
                }
            },
        )
    }

    private fun deferred(
        name: String,
        description: String?,
        server: String = "test-server",
        parameters: JsonObject = toolSchema(),
    ): McpDeferredTool = McpDeferredTool(name, description, server, parameters)

    private fun searchFor(
        tools: List<McpDeferredTool>,
        query: String,
        limit: Int = 8,
    ): List<McpDeferredTool> = McpToolSearch({ tools }, onLoaded = {}).search(query, limit)

    private fun sampleCatalog(): List<McpDeferredTool> = listOf(
        deferred("search_web", "Search the web for pages", server = "web"),
        deferred("read_file", "Read a file from disk", server = "fs"),
        deferred("sql_query", "Run a SQL query on the database connection", server = "db"),
    )

    @Test
    fun englishQueryMatchesNameAndDescription() {
        val results = searchFor(sampleCatalog(), "web pages")
        assertEquals(listOf("search_web"), results.map { it.name })
    }

    @Test
    fun englishQueryMatchesCamelCaseName() {
        val tools = listOf(deferred("listMcpResources", "List resources"))
        val results = searchFor(tools, "mcp resources")
        assertEquals(listOf("listMcpResources"), results.map { it.name })
    }

    @Test
    fun englishQueryMatchesSchemaPropertyNames() {
        val tools = listOf(
            deferred("remote_call", "Call a remote endpoint", parameters = toolSchema("endpointUrl")),
            deferred("unrelated_tool", "Unrelated helper"),
        )
        val results = searchFor(tools, "endpointUrl")
        assertEquals(listOf("remote_call"), results.map { it.name })
    }

    @Test
    fun englishStemMatchesPluralQuery() {
        val tools = listOf(deferred("create_issue", "Create a tracker issue"))
        val results = searchFor(tools, "issues")
        assertEquals(listOf("create_issue"), results.map { it.name })
    }

    @Test
    fun cjkQueryMatchesChineseDescription() {
        val tools = listOf(
            deferred("web_search", "在互联网中搜索网页内容", server = "web"),
            deferred("read_file", "读取磁盘上的文件内容", server = "fs"),
        )
        val results = searchFor(tools, "搜索网页")
        assertEquals(listOf("web_search"), results.map { it.name })
    }

    @Test
    fun cjkBigramsCrossWordBoundaries() {
        val tools = listOf(deferred("db_query", "执行数据库查询"))
        val results = searchFor(tools, "数据库")
        assertEquals(listOf("db_query"), results.map { it.name })
    }

    @Test
    fun cjkQueryMatchesTwoGramInsideDescription() {
        val tools = listOf(deferred("translate_text", "翻译文本"))
        val results = searchFor(tools, "文本")
        assertEquals(listOf("translate_text"), results.map { it.name })
    }

    @Test
    fun limitCapsResults() {
        val tools = (1..5).map { index -> deferred("search_$index", "search helper number $index") }
        assertEquals(2, searchFor(tools, "search helper", limit = 2).size)
        assertEquals(5, searchFor(tools, "search helper", limit = 50).size)
    }

    @Test
    fun emptyQueryOrNonPositiveLimitReturnsEmpty() {
        val tools = sampleCatalog()
        assertTrue(searchFor(tools, "").isEmpty())
        assertTrue(searchFor(tools, "   ").isEmpty())
        assertTrue(searchFor(tools, "web", limit = 0).isEmpty())
        assertTrue(searchFor(tools, "web", limit = -3).isEmpty())
    }

    @Test
    fun tiesAreOrderedByName() {
        val tools = listOf(
            deferred("beta_tool", "same shared description"),
            deferred("alpha_tool", "same shared description"),
        )
        val results = searchFor(tools, "shared description")
        assertEquals(listOf("alpha_tool", "beta_tool"), results.map { it.name })
    }

    @Test
    fun toolSearchToolReturnsLoadedTextAndDetails() = runTest {
        var loadedNames: List<String> = emptyList()
        val search = McpToolSearch({ sampleCatalog() }, onLoaded = { loadedNames = it })
        val result = search.toCodingTool().execute(
            "test-call",
            buildJsonObject { put("query", "web pages") },
            null,
            null,
        )
        assertEquals(listOf("search_web"), loadedNames)
        val text = result.text()
        assertTrue(text.startsWith("Loaded 1 tool. They are available from your next call:\n- search_web: Search the web for pages"))
        val details = result.details as JsonObject
        assertEquals(
            listOf("search_web"),
            (details["loaded"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun toolSearchToolNoMatchReturnsEmptyLoaded() = runTest {
        var called = false
        val search = McpToolSearch({ sampleCatalog() }, onLoaded = { called = true })
        val result = search.toCodingTool().execute(
            "test-call",
            buildJsonObject { put("query", "zzzz-not-present") },
            null,
            null,
        )
        assertEquals("No matching tools found.", result.text())
        assertEquals(false, called)
        val details = result.details as JsonObject
        assertEquals(0, (details["loaded"] as JsonArray).size)
    }

    @Test
    fun toolSearchToolRespectsLimitArgument() = runTest {
        val tools = (1..5).map { index -> deferred("search_$index", "search helper number $index") }
        val search = McpToolSearch({ tools }, onLoaded = {})
        val result = search.toCodingTool().execute(
            "test-call",
            buildJsonObject {
                put("query", "search helper")
                put("limit", JsonPrimitive(2))
            },
            null,
            null,
        )
        val details = result.details as JsonObject
        assertEquals(2, (details["loaded"] as JsonArray).size)
        assertTrue(result.text().startsWith("Loaded 2 tools."))
    }

    @Test
    fun toolSearchToolRejectsInvalidArguments() = runTest {
        val search = McpToolSearch({ sampleCatalog() }, onLoaded = {})
        val tool = search.toCodingTool()
        val emptyQuery = runCatching {
            tool.execute("test-call", buildJsonObject { put("query", "  ") }, null, null)
        }.exceptionOrNull()
        assertTrue(emptyQuery is IllegalArgumentException)
        val badLimit = runCatching {
            tool.execute(
                "test-call",
                buildJsonObject {
                    put("query", "web")
                    put("limit", JsonPrimitive(0))
                },
                null,
                null,
            )
        }.exceptionOrNull()
        assertTrue(badLimit is IllegalArgumentException)
    }
}

private fun AgentToolResult.text(): String =
    content.filterIsInstance<TextContent>().joinToString("") { it.text }
