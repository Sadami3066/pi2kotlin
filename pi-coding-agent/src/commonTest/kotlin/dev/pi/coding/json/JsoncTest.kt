package dev.pi.coding.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsoncTest {
    @Test
    fun parsesPlainJson() {
        assertEquals(
            Json.parseToJsonElement("""{"a":1}"""),
            Jsonc.parse("""{"a":1}"""),
        )
    }

    @Test
    fun stripsLineComments() {
        val text = """
            {
              // preference comment
              "theme": "dark", // trailing comment
              "retry": { "enabled": true }
            }
        """.trimIndent()
        val parsed = Jsonc.parse(text).jsonObject
        assertEquals("dark", parsed["theme"]?.jsonPrimitive?.content)
    }

    @Test
    fun stripsBlockComments() {
        val text = """
            {
              /* multiline
                 block comment */
              "defaultModel": "deepseek-v4-flash" /* inline */,
              "value": 7
            }
        """.trimIndent()
        val parsed = Jsonc.parse(text).jsonObject
        assertEquals("deepseek-v4-flash", parsed["defaultModel"]?.jsonPrimitive?.content)
        assertEquals("7", parsed["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun allowsTrailingCommasInObjectsAndArrays() {
        val text = """
            {
              "a": 1,
              "b": [1, 2, 3,],
            }
        """.trimIndent()
        val parsed = Jsonc.parse(text).jsonObject
        assertEquals("1", parsed["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun keepsCommentMarkersInsideStrings() {
        val text = """{"url":"http://example.com//path","note":"a /* b */ c","comma":",}"}"""
        val parsed = Jsonc.parse(text).jsonObject
        assertEquals("http://example.com//path", parsed["url"]?.jsonPrimitive?.content)
        assertEquals("a /* b */ c", parsed["note"]?.jsonPrimitive?.content)
        assertEquals(",}", parsed["comma"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesEmptyObjectAndArray() {
        assertEquals(Json.parseToJsonElement("{}"), Jsonc.parse("{}"))
        assertEquals(Json.parseToJsonElement("[]"), Jsonc.parse("[]"))
    }

    @Test
    fun parsesScalars() {
        assertEquals(Json.parseToJsonElement("\"text\""), Jsonc.parse("\"text\""))
        assertEquals(Json.parseToJsonElement("42"), Jsonc.parse("42"))
        assertEquals(Json.parseToJsonElement("true"), Jsonc.parse("true"))
    }

    @Test
    fun rejectsInvalidJson() {
        assertFailsWith<IllegalArgumentException> { Jsonc.parse("{invalid") }
        assertFailsWith<IllegalArgumentException> { Jsonc.parse("") }
        assertFailsWith<IllegalArgumentException> { Jsonc.parse("""{"a":1} trailing""") }
        assertFailsWith<IllegalArgumentException> { Jsonc.parse("""{"a":1,,"b":2}""") }
    }

    @Test
    fun rejectsUnterminatedBlockComment() {
        assertFailsWith<IllegalArgumentException> { Jsonc.parse("""{"a":1} /* unterminated""") }
    }
}
