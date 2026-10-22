package dev.pi.ai.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialJsonTest {
    @Test
    fun parsesCompleteObject() {
        assertEquals(
            Json.parseToJsonElement("""{"a":1,"b":[true,null],"c":"x"}"""),
            PartialJson.parse("""{"a":1,"b":[true,null],"c":"x"}"""),
        )
    }

    @Test
    fun parsesCompleteArray() {
        assertEquals(Json.parseToJsonElement("""[1,2,3]"""), PartialJson.parse("""[1,2,3]"""))
    }

    @Test
    fun parsesPartialObjectWithDanglingKey() {
        assertEquals(Json.parseToJsonElement("""{"a":1}"""), PartialJson.parse("""{"a":1,"b":"""))
    }

    @Test
    fun parsesIncompleteStringValue() {
        assertEquals(Json.parseToJsonElement("""{"a":"hel"}"""), PartialJson.parse("""{"a":"hel"""))
    }

    @Test
    fun parsesIncompleteNumberValue() {
        assertEquals(Json.parseToJsonElement("""{"a":12}"""), PartialJson.parse("""{"a":12"""))
    }

    @Test
    fun parsesIncompleteArrayValue() {
        assertEquals(Json.parseToJsonElement("""{"a":[1,2]}"""), PartialJson.parse("""{"a":[1,2"""))
    }

    @Test
    fun parsesNestedIncompleteStructure() {
        assertEquals(
            Json.parseToJsonElement("""{"a":{"b":[{}]}}"""),
            PartialJson.parse("""{"a":{"b":[{"c""""),
        )
    }

    @Test
    fun parsesEmptyContainers() {
        assertEquals(Json.parseToJsonElement("""[]"""), PartialJson.parse("["))
        assertEquals(Json.parseToJsonElement("""{"a":{}}"""), PartialJson.parse("""{"a":{"""))
        assertEquals(Json.parseToJsonElement("""{"a":[]}"""), PartialJson.parse("""{"a":["""))
    }

    @Test
    fun keepsInvalidUnicodeEscapesAsLiteralText() {
        assertEquals(
            Json.parseToJsonElement("""{"text":"\\url{x}"}"""),
            PartialJson.parse("""{"text":"\url{x}"}"""),
        )
    }

    @Test
    fun decodesValidUnicodeEscapes() {
        assertEquals(
            Json.parseToJsonElement("""{"text":"caf\u00e9 \\url"}"""),
            PartialJson.parse("""{"text":"caf\u00e9 \url"}"""),
        )
    }

    @Test
    fun omitsUnicodeEscapeCutOffMidStream() {
        assertEquals(Json.parseToJsonElement("""{"text":"ab"}"""), PartialJson.parse("""{"text":"ab\u00"""))
    }

    @Test
    fun repairsRawControlCharactersInsideStrings() {
        val raw = "{\"text\":\"col1\tcol2\"}"
        assertEquals(Json.parseToJsonElement("""{"text":"col1\tcol2"}"""), PartialJson.parse(raw))
    }

    @Test
    fun parsesPartiallyStreamedNestedToolArguments() {
        val accumulated = """{"path":"src/main.kt","content":"fun main() {"""
        val parsed = assertIs<JsonObject>(PartialJson.parse(accumulated))
        assertEquals("src/main.kt", (parsed["path"] as JsonPrimitive).content)
        assertEquals("fun main() {", (parsed["content"] as JsonPrimitive).content)
    }

    @Test
    fun returnsNullForEmptyInput() {
        assertNull(PartialJson.parse(""))
        assertNull(PartialJson.parse("   \n\t "))
    }

    @Test
    fun returnsNullForNonJsonGarbage() {
        assertNull(PartialJson.parse("wrong"))
    }

    @Test
    fun parsesMalformedNumberAsEmptyObjectLikePartialJson() {
        assertEquals(JsonObject(emptyMap()), PartialJson.parse("""{"a": .5}"""))
    }

    @Test
    fun repairsInvalidEscapesByDoublingBackslashes() {
        assertEquals(
            """{"text":"\\url{x}"}""",
            PartialJson.repair("""{"text":"\url{x}"}"""),
        )
        assertEquals(
            """{"path":"a.tex","content":"\\usepackage{x}","mode":"w"}""",
            PartialJson.repair("""{"path":"a.tex","content":"\usepackage{x}","mode":"w"}"""),
        )
    }

    @Test
    fun repairKeepsValidEscapes() {
        val valid = """{"text":"line\nbreak\ttab\"quote\\slash\/solid"}"""
        assertEquals(valid, PartialJson.repair(valid))
    }

    @Test
    fun repairEscapesRawControlCharacters() {
        val raw = "{\"a\":\"line1\nline2\ttab\"}"
        assertEquals("""{"a":"line1\nline2\ttab"}""", PartialJson.repair(raw))
    }

    @Test
    fun repairDoublesTrailingBackslash() {
        assertEquals("{\"a\":\"" + "\\\\", PartialJson.repair("{\"a\":\"" + "\\"))
    }

    @Test
    fun repairDropsIncompleteUnicodeEscape() {
        assertEquals("{\"a\":\"" + "\\\\u00", PartialJson.repair("{\"a\":\"" + "\\u00"))
    }

    @Test
    fun parseToolCallArgumentsParsesCompleteJson() {
        val result = PartialJson.parseToolCallArguments("""{"kind":"seek","note":"\url"}""")
        assertNull(result.argumentsError)
        assertEquals(Json.parseToJsonElement("""{"kind":"seek","note":"\\url"}"""), result.arguments)
    }

    @Test
    fun parseToolCallArgumentsUsesEmptyArgumentsForEmptyText() {
        assertEquals(ToolArguments(JsonObject(emptyMap())), PartialJson.parseToolCallArguments(null))
        assertEquals(ToolArguments(JsonObject(emptyMap())), PartialJson.parseToolCallArguments(""))
        assertEquals(ToolArguments(JsonObject(emptyMap())), PartialJson.parseToolCallArguments("   "))
    }

    @Test
    fun parseToolCallArgumentsRejectsTruncatedJson() {
        val raw = """{"kind":"seek","clipId":"clip-fl"""
        val result = PartialJson.parseToolCallArguments(raw)
        assertEquals(JsonObject(emptyMap()), result.arguments)
        val error = assertNotNull(result.argumentsError)
        assertTrue(error.contains("\n\nReceived arguments:\n"))
        assertTrue(error.endsWith(raw))
    }

    @Test
    fun parseToolCallArgumentsDoesNotKeepValidPrefixOfMalformedJson() {
        val raw = """{"action":"apply","actions":[{"kind":"seek","time":.5}]}"""
        val result = PartialJson.parseToolCallArguments(raw)
        assertEquals(JsonObject(emptyMap()), result.arguments)
        val error = assertNotNull(result.argumentsError)
        assertTrue(error.contains("\n\nReceived arguments:\n"))
        assertTrue(error.endsWith(raw))
    }

    @Test
    fun parseToolCallArgumentsRejectsNonObjectJson() {
        val result = PartialJson.parseToolCallArguments("[1,2]")
        assertEquals(JsonObject(emptyMap()), result.arguments)
        assertEquals("Tool arguments must be a JSON object\n\nReceived arguments:\n[1,2]", result.argumentsError)
    }

    @Test
    fun parseToolCallArgumentsRejectsJsonNull() {
        val result = PartialJson.parseToolCallArguments("null")
        assertEquals(JsonObject(emptyMap()), result.arguments)
        assertEquals("Tool arguments must be a JSON object\n\nReceived arguments:\nnull", result.argumentsError)
    }
}
