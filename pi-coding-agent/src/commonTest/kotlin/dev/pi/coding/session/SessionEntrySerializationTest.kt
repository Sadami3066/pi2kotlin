package dev.pi.coding.session

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SessionEntrySerializationTest {
    private val timestamp = "2026-10-22T01:40:58.902Z"

    @Test
    fun roundTripsSessionHeader() {
        val header = SessionHeader(
            type = "session",
            version = 3,
            id = "019eab96-b0e8-7851-921b-3f2f565d9240",
            timestamp = timestamp,
            cwd = "/home/whywood/project/pi",
            parentSession = "/tmp/parent.jsonl",
        )
        val decoded = decodeSessionHeader(sessionJson.parseToJsonElement(encodeSessionHeader(header)).jsonObject)
        assertEquals(header, decoded)
    }

    @Test
    fun roundTripsMessageEntry() {
        val entry = SessionMessageEntry("a1b2c3d4", "f6e5d4c3", timestamp, userMessage("hello"))
        val json = encodeSessionEntry(entry)
        val obj = sessionJson.parseToJsonElement(json).jsonObject
        assertEquals("message", obj["type"]?.jsonPrimitive?.content)
        assertEquals("a1b2c3d4", obj["id"]?.jsonPrimitive?.content)
        assertEquals("f6e5d4c3", obj["parentId"]?.jsonPrimitive?.content)
        val decoded = assertIs<SessionMessageEntry>(decodeSessionEntry(obj))
        assertEquals(entry.id, decoded.id)
        assertEquals(entry.parentId, decoded.parentId)
        assertEquals(entry.timestamp, decoded.timestamp)
        assertEquals(entry.message, decoded.message)
        assertEquals(obj["message"], decoded.rawMessage)
    }

    @Test
    fun encodesNullParentIdExplicitly() {
        val entry = SessionMessageEntry("root0001", null, timestamp, userMessage("root"))
        val obj = sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject
        assertTrue(obj.containsKey("parentId"))
        assertEquals(JsonNull, obj["parentId"])
    }

    @Test
    fun roundTripsStateEntries() {
        val usage = sampleUsage()
        val entries = listOf(
            ThinkingLevelChangeEntry("t1", null, timestamp, "max"),
            ModelChangeEntry("m1", "t1", timestamp, "deepseek", "deepseek-v4-pro"),
            UsageEntry("u1", "m1", timestamp, kind = "cache_warm", provider = "anthropic", model = "claude", usage = usage, note = "warm"),
            CompactionEntry(
                id = "c1",
                parentId = "u1",
                timestamp = timestamp,
                summary = "summary",
                firstKeptEntryId = "a1b2c3d4",
                tokensBefore = 50000,
                usage = usage,
                details = buildJsonObject { put("readFiles", JsonPrimitive("a")) },
                fromHook = false,
                systemMessage = SystemMessage(content = PlainTextContent("prompt"), timestamp = 1000),
            ),
            BranchSummaryEntry("b1", "c1", timestamp, fromId = "leaf", summary = "branch", usage = usage),
            CustomEntry("x1", "b1", timestamp, customType = "pi.virtual-model-state", data = buildJsonObject { put("count", 42) }),
            CustomMessageEntry("cm1", "x1", timestamp, customType = "ext", content = PlainTextContent("injected"), display = true),
            ContextEditEntry("ce1", "cm1", timestamp, targetId = "a1b2c3d4", replacement = null),
            ContextEditEntry(
                "ce2",
                "ce1",
                timestamp,
                targetId = "a1b2c3d4",
                replacement = ContentReplacement(BlockContent(listOf(TextContent("replaced")))),
            ),
            LabelEntry("l1", "ce2", timestamp, targetId = "a1b2c3d4", label = "checkpoint-1"),
            LabelEntry("l2", "l1", timestamp, targetId = "a1b2c3d4", label = null),
            SessionInfoEntry("s1", "l2", timestamp, name = "Refactor auth"),
            SessionInfoEntry("s2", "s1", timestamp, name = null),
        )
        assertRoundTrips(entries)
    }

    @Test
    fun encodesContextEditNullReplacementExplicitly() {
        val entry = ContextEditEntry("ce1", null, timestamp, targetId = "a1b2c3d4", replacement = null)
        val obj = sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject
        assertTrue(obj.containsKey("replacement"))
        assertEquals(JsonNull, obj["replacement"])
    }

    @Test
    fun encodesContextEditReplacementAsContentObject() {
        val entry = ContextEditEntry(
            "ce1",
            null,
            timestamp,
            targetId = "a1b2c3d4",
            replacement = ContentReplacement(PlainTextContent("replaced")),
        )
        val obj = sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject
        val replacement = obj["replacement"]?.jsonObject
        assertNotNull(replacement)
        assertEquals("replaced", replacement["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun roundTripsAssistantAndToolResultMessages() {
        val assistant = AssistantMessage(
            content = mutableListOf(TextContent("done")),
            api = "openai-completions",
            provider = "deepseek",
            model = "deepseek-v4-flash",
            usage = sampleUsage(),
            stopReason = StopReason.Stop,
            timestamp = 1_792_633_265_000L,
        )
        val toolResult = ToolResultMessage(
            toolCallId = "call_1",
            toolName = "bash",
            content = listOf(TextContent("ok")),
            isError = false,
            timestamp = 1_792_633_267_000L,
        )
        val entries = listOf(
            SessionMessageEntry("aa11bb22", null, timestamp, assistant),
            SessionMessageEntry("cc33dd44", "aa11bb22", timestamp, toolResult),
        )
        for (entry in entries) {
            val decoded = assertIs<SessionMessageEntry>(
                decodeSessionEntry(sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject),
            )
            assertEquals(entry.message, decoded.message)
        }
    }

    @Test
    fun decodesBashExecutionMessageAndKeepsRawJson() {
        val raw = buildJsonObject {
            put("role", "bashExecution")
            put("command", "git status")
            put("output", "clean")
            put("exitCode", 0)
            put("cancelled", false)
            put("truncated", false)
            put("excludeFromContext", true)
            put("timestamp", 1_792_633_267_000L)
        }
        val entry = SessionMessageEntry("ee55ff66", null, timestamp, userMessage("placeholder"), raw)
        val encoded = sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject
        assertEquals("bashExecution", encoded["message"]?.jsonObject?.get("role")?.jsonPrimitive?.content)
        val decoded = assertIs<SessionMessageEntry>(decodeSessionEntry(encoded))
        val message = assertIs<UserMessage>(decoded.message)
        val text = (message.content as BlockContent).blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("Ran `git status`\n```\nclean\n```", text)
        assertEquals(raw, decoded.rawMessage)
        assertTrue(decoded.isExcludedFromContext())
    }

    @Test
    fun decodesCustomMessageEntryIntoUserMessage() {
        val raw = buildJsonObject {
            put("role", "custom")
            put("customType", "ext")
            put("content", "injected text")
            put("display", true)
            put("timestamp", 1_792_633_267_000L)
        }
        val entry = SessionMessageEntry("7711aa22", null, timestamp, userMessage("placeholder"), raw)
        val decoded = assertIs<SessionMessageEntry>(
            decodeSessionEntry(sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject),
        )
        val message = assertIs<UserMessage>(decoded.message)
        val blocks = (message.content as BlockContent).blocks
        assertEquals("injected text", (blocks.single() as TextContent).text)
    }

    @Test
    fun decodesMessageWithMissingContent() {
        val raw = buildJsonObject {
            put("role", "assistant")
            put("content", JsonNull)
            put("api", "test")
            put("provider", "test")
            put("model", "test")
            put("usage", sampleUsageJson())
            put("stopReason", "stop")
            put("timestamp", 1_792_633_265_000L)
        }
        val entry = SessionMessageEntry("8811bb22", null, timestamp, userMessage("placeholder"), raw)
        val decoded = assertIs<SessionMessageEntry>(
            decodeSessionEntry(sessionJson.parseToJsonElement(encodeSessionEntry(entry)).jsonObject),
        )
        val message = assertIs<AssistantMessage>(decoded.message)
        assertEquals(emptyList(), message.content)
    }

    @Test
    fun decodesUnknownEntryTypeAsNull() {
        val obj = buildJsonObject {
            put("type", "future_entry")
            put("id", "zzz")
            put("parentId", JsonNull)
            put("timestamp", timestamp)
        }
        assertNull(decodeSessionEntry(obj))
    }

    @Test
    fun ignoresUnknownFieldsWhenDecoding() {
        val obj = buildJsonObject {
            put("type", "session_info")
            put("id", "si")
            put("parentId", JsonNull)
            put("timestamp", timestamp)
            put("name", "named")
            put("futureField", buildJsonObject { put("x", 1) })
        }
        val decoded = assertIs<SessionInfoEntry>(decodeSessionEntry(obj))
        assertEquals("named", decoded.name)
    }

    @Test
    fun labelsAndNamesAreOptionalInJson() {
        val label = assertIs<LabelEntry>(
            decodeSessionEntry(
                buildJsonObject {
                    put("type", "label")
                    put("id", "l")
                    put("parentId", JsonNull)
                    put("timestamp", timestamp)
                    put("targetId", "target")
                },
            ),
        )
        assertNull(label.label)
        val info = assertIs<SessionInfoEntry>(
            decodeSessionEntry(
                buildJsonObject {
                    put("type", "session_info")
                    put("id", "s")
                    put("parentId", JsonNull)
                    put("timestamp", timestamp)
                },
            ),
        )
        assertNull(info.name)
    }

    private fun assertRoundTrips(entries: List<SessionEntry>) {
        for (entry in entries) {
            val json = encodeSessionEntry(entry)
            val decoded = assertNotNull(decodeSessionEntry(sessionJson.parseToJsonElement(json).jsonObject))
            assertEquals(entry, decoded)
        }
    }

    private fun sampleUsage(): Usage = Usage(
        input = 100,
        output = 20,
        cacheRead = 5,
        cacheWrite = 3,
        totalTokens = 128,
        cost = UsageCost(input = 0.01, output = 0.02, cacheRead = 0.0, cacheWrite = 0.0, total = 0.03),
    )

    private fun sampleUsageJson(): JsonObject = buildJsonObject {
        put("input", 100)
        put("output", 20)
        put("cacheRead", 5)
        put("cacheWrite", 3)
        put("totalTokens", 128)
        put(
            "cost",
            buildJsonObject {
                put("input", 0.01)
                put("output", 0.02)
                put("cacheRead", 0.0)
                put("cacheWrite", 0.0)
                put("total", 0.03)
            },
        )
    }

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = PlainTextContent(text), timestamp = 1_792_633_265_000L)
}
