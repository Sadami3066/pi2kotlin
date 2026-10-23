package dev.pi.coding.mcp

import dev.pi.mcp.protocol.McpPrompt
import dev.pi.mcp.protocol.McpPromptArgument
import dev.pi.mcp.protocol.McpPromptResult
import dev.pi.mcp.protocol.McpPromptsPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpPromptsTest {
    private class FakePromptOperations(
        override val serverName: String,
        private val pages: List<McpPromptsPage>,
        private val result: McpPromptResult = McpPromptResult(),
    ) : McpPromptOperations {
        var lastName: String? = null
        var lastArguments: Map<String, String>? = null
        var pageCalls: Int = 0

        override suspend fun listPrompts(cursor: String?): McpPromptsPage {
            pageCalls++
            val index = cursor?.toIntOrNull() ?: 0
            return pages.getOrElse(index) { McpPromptsPage(emptyList()) }
        }

        override suspend fun getPrompt(name: String, arguments: Map<String, String>): McpPromptResult {
            lastName = name
            lastArguments = arguments
            return result
        }
    }

    private fun prompt(name: String, title: String? = null, description: String? = null): McpPrompt = McpPrompt(
        name = name,
        title = title,
        description = description,
        arguments = listOf(McpPromptArgument(name = "topic", description = "Topic", required = true)),
    )

    private fun page(vararg prompts: McpPrompt, nextCursor: String? = null): McpPromptsPage =
        McpPromptsPage(prompts = prompts.toList(), nextCursor = nextCursor)

    private fun textMessage(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put(
            "content",
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }

    private fun imageMessage(role: String): JsonObject = buildJsonObject {
        put("role", role)
        put(
            "content",
            buildJsonObject {
                put("type", "image")
                put("data", "aGVsbG8=")
                put("mimeType", "image/png")
            },
        )
    }

    private fun resourceMessage(role: String, text: String?, uri: String): JsonObject = buildJsonObject {
        put("role", role)
        put(
            "content",
            buildJsonObject {
                put("type", "resource")
                put(
                    "resource",
                    buildJsonObject {
                        put("uri", uri)
                        if (text != null) put("text", text)
                    },
                )
            },
        )
    }

    @Test
    fun listMapsPromptInfoAndSortsByServerThenName() = runTest {
        val serverB = FakePromptOperations("zeta", listOf(page(prompt("review"), prompt("audit"))))
        val serverA = FakePromptOperations("alpha", listOf(page(prompt("summarize", title = "Summarize", description = "Summarizes"))))
        val operations = mapOf("zeta" to serverB, "alpha" to serverA)
        val prompts = McpPrompts().listOperations(listOf("zeta", "alpha", "zeta")) { name -> operations[name] }
        assertEquals(listOf("alpha/summarize", "zeta/audit", "zeta/review"), prompts.map { "${it.server}/${it.name}" })
        val summarize = prompts.first()
        assertEquals("Summarize", summarize.title)
        assertEquals("Summarizes", summarize.description)
        assertEquals(listOf("topic"), summarize.arguments.map { it.name })
        assertEquals(true, summarize.arguments.first().required)
    }

    @Test
    fun listFollowsPagination() = runTest {
        val client = FakePromptOperations(
            "server",
            listOf(
                page(prompt("one"), nextCursor = "1"),
                page(prompt("two"), nextCursor = null),
            ),
        )
        val prompts = McpPrompts().listOperations(listOf("server")) { client }
        assertEquals(listOf("one", "two"), prompts.map { it.name })
        assertEquals(2, client.pageCalls)
    }

    @Test
    fun listSkipsMissingClients() = runTest {
        val client = FakePromptOperations("known", listOf(page(prompt("known_prompt"))))
        val prompts = McpPrompts().listOperations(listOf("known", "missing")) { name ->
            if (name == "known") client else null
        }
        assertEquals(listOf("known_prompt"), prompts.map { it.name })
    }

    @Test
    fun listWithoutServerNamesReturnsEmpty() = runTest {
        val prompts = McpPrompts().list(server = null) { null }
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun expandJoinsMessagesWithBlankLine() = runTest {
        val result = McpPromptResult(
            messages = listOf(
                textMessage("user", "first paragraph"),
                textMessage("assistant", "second paragraph"),
            ),
        )
        val client = FakePromptOperations("server", listOf(page()), result = result)
        val expanded = McpPrompts().expandOperations(client, "review", mapOf("topic" to "security"))
        assertEquals("first paragraph\n\nsecond paragraph", expanded)
        assertEquals("review", client.lastName)
        assertEquals(mapOf("topic" to "security"), client.lastArguments)
    }

    @Test
    fun expandNotesImageContent() = runTest {
        val result = McpPromptResult(
            messages = listOf(
                textMessage("user", "look at this"),
                imageMessage("user"),
                textMessage("assistant", "done"),
            ),
        )
        val client = FakePromptOperations("server", listOf(page()), result = result)
        val expanded = McpPrompts().expandOperations(client, "vision")
        assertEquals("look at this\n\n[image content omitted]\n\ndone", expanded)
    }

    @Test
    fun expandRendersResourceTextAndOmittedUri() = runTest {
        val result = McpPromptResult(
            messages = listOf(
                resourceMessage("user", "resource body", "file:///a.txt"),
                resourceMessage("user", null, "file:///b.bin"),
            ),
        )
        val client = FakePromptOperations("server", listOf(page()), result = result)
        val expanded = McpPrompts().expandOperations(client, "resources")
        assertEquals("resource body\n\n[resource content omitted: file:///b.bin]", expanded)
    }

    @Test
    fun expandSkipsUnknownRoles() = runTest {
        val result = McpPromptResult(
            messages = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", JsonPrimitive("ignored system text"))
                },
                textMessage("user", "kept"),
            ),
        )
        val client = FakePromptOperations("server", listOf(page()), result = result)
        val expanded = McpPrompts().expandOperations(client, "mixed")
        assertEquals("kept", expanded)
    }

    @Test
    fun expandUsesDefaultEmptyArguments() = runTest {
        val client = FakePromptOperations("server", listOf(page()), result = McpPromptResult())
        val expanded = McpPrompts().expandOperations(client, "empty")
        assertEquals("", expanded)
        assertEquals(emptyMap(), client.lastArguments)
    }
}
