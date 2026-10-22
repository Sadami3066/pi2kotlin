package dev.pi.ai.utils

import dev.pi.ai.JsonValue
import dev.pi.ai.PiJson
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ValidationTest {
    private fun parse(text: String): JsonValue = PiJson.parseToJsonElement(text)

    private fun tool(parameters: String): Tool = Tool(
        name = "echo",
        description = "Echo tool",
        parameters = parse(parameters).jsonObject,
    )

    private fun call(name: String = "echo", arguments: String, argumentsError: String? = null): ToolCall = ToolCall(
        id = "tool-1",
        name = name,
        arguments = parse(arguments).jsonObject,
        argumentsError = argumentsError,
    )

    private fun assertResult(expected: String, actual: JsonObject) {
        assertEquals(parse(expected), actual)
    }

    private fun assertErrorMessage(expected: String, block: () -> Unit) {
        val error = assertFailsWith<ToolArgumentValidationException> { block() }
        assertEquals(expected, error.message)
    }

    private fun valueTool(schema: String): Tool = tool(
        """{"type":"object","properties":{"value":$schema},"required":["value"]}""",
    )

    @Test
    fun coercesSerializedPlainJsonSchemasWithAjvCompatiblePrimitiveRules() {
        data class Case(val schema: String, val input: String, val expected: String)

        val cases = listOf(
            Case("""{"type":"number"}""", "42", "42"),
            Case("""{"type":"number"}""", "true", "1"),
            Case("""{"type":"number"}""", "null", "0"),
            Case("""{"type":"integer"}""", "42", "42"),
            Case("""{"type":"integer"}""", "\"1e3\"", "1000"),
            Case("""{"type":"integer"}""", "true", "1"),
            Case("""{"type":"integer"}""", "\"0x10\"", "16"),
            Case("""{"type":"boolean"}""", "\"true\"", "true"),
            Case("""{"type":"boolean"}""", "\"false\"", "false"),
            Case("""{"type":"boolean"}""", "1", "true"),
            Case("""{"type":"boolean"}""", "0", "false"),
            Case("""{"type":"string"}""", "null", "\"\""),
            Case("""{"type":"string"}""", "true", "\"true\""),
            Case("""{"type":"string"}""", "4.5", "\"4.5\""),
            Case("""{"type":"null"}""", "\"\"", "null"),
            Case("""{"type":"null"}""", "0", "null"),
            Case("""{"type":"null"}""", "false", "null"),
            Case("""{"type":["number","string"]}""", "\"1\"", "\"1\""),
            Case("""{"type":["boolean","number"]}""", "\"1\"", "1"),
        )

        for (case in cases) {
            val result = validateToolArguments(valueTool(case.schema), call(arguments = """{"value":${case.input}}"""))
            assertResult("""{"value":${case.expected}}""", result)
        }
    }

    @Test
    fun rejectsInvalidCoercionsForSerializedPlainJsonSchemas() {
        val cases = listOf(
            Triple("""{"type":"boolean"}""", "\"1\"", "must be boolean"),
            Triple("""{"type":"boolean"}""", "\"0\"", "must be boolean"),
            Triple("""{"type":"boolean"}""", "\"TRUE\"", "must be boolean"),
            Triple("""{"type":"null"}""", "\"null\"", "must be null"),
            Triple("""{"type":"integer"}""", "\"42.1\"", "must be integer"),
        )
        for ((schema, input, message) in cases) {
            assertErrorMessage(
                "Validation failed for tool \"echo\":\n  - value: $message\n\nReceived arguments:\n{\n  \"value\": $input\n}",
            ) {
                validateToolArguments(valueTool(schema), call(arguments = """{"value":$input}"""))
            }
        }
    }

    @Test
    fun treatsNullAsOmissionForOptionalNonNullableProperties() {
        val tool = tool(
            """
            {"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"number"},"nullable":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{"type":"object","properties":{"enabled":{"type":"boolean"}}}},"required":["path"]}
            """.trimIndent(),
        )
        val result = validateToolArguments(
            tool,
            call(arguments = """{"path":"file.txt","offset":null,"nullable":null,"metadata":{"enabled":null}}"""),
        )
        assertResult("""{"path":"file.txt","nullable":null,"metadata":{}}""", result)
    }

    @Test
    fun preservesOptionalNullsWhoseSchemaIsAReference() {
        val tool = tool(
            """{"type":"object","properties":{"value":{"${'$'}ref":"#/${'$'}defs/value"}},"${'$'}defs":{"value":{"anyOf":[{"type":"number"},{"type":"null"}]}}}""",
        )
        assertResult("""{"value":null}""", validateToolArguments(tool, call(arguments = """{"value":null}""")))
    }

    @Test
    fun preservesValueThatAlreadyMatchesNullableUnionArm() {
        val tool = tool("""{"type":"object","properties":{"value":{"anyOf":[{"type":"number"},{"type":"null"}]}},"required":["value"]}""")
        assertResult("""{"value":null}""", validateToolArguments(tool, call(arguments = """{"value":null}""")))
    }

    @Test
    fun preservesValueThatMatchesOneOfNullableUnionArm() {
        val tool = valueTool("""{"oneOf":[{"type":"number"},{"type":"null"}]}""")
        assertResult("""{"value":null}""", validateToolArguments(tool, call(arguments = """{"value":null}""")))
    }

    @Test
    fun coercesNullableUnionWhenOriginalValueDoesNotMatchAnyArm() {
        val tool = valueTool("""{"anyOf":[{"type":"number"},{"type":"null"}]}""")
        assertResult("""{"value":42}""", validateToolArguments(tool, call(arguments = """{"value":"42"}""")))
    }

    @Test
    fun acceptsNullForNullableArraySchemasWithItems() {
        val tool = valueTool("""{"type":["array","null"],"items":{"type":"string"}}""")
        assertResult("""{"value":null}""", validateToolArguments(tool, call(arguments = """{"value":null}""")))
    }

    @Test
    fun reportsMalformedArgumentsJsonInsteadOfValidatingEmptyArguments() {
        val tool = valueTool("""{"type":"string"}""")
        assertErrorMessage("Invalid arguments for tool \"echo\": Unexpected token") {
            validateToolArguments(tool, call(arguments = "{}", argumentsError = "Unexpected token"))
        }
    }

    @Test
    fun reportsMissingRequiredPropertiesWithVerbatimMessage() {
        val tool = tool(
            """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a","b"]}""",
        )
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - b: must have required properties b

            Received arguments:
            {
              "a": "x"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"a":"x"}"""))
        }
    }

    @Test
    fun reportsNestedRequiredPropertiesWithDottedPath() {
        val tool = tool(
            """{"type":"object","properties":{"outer":{"type":"object","properties":{"inner":{"type":"string"}},"required":["inner"]}},"required":["outer"]}""",
        )
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - outer.inner: must have required properties inner

            Received arguments:
            {
              "outer": {}
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"outer":{}}"""))
        }
    }

    @Test
    fun reportsAdditionalPropertiesWithVerbatimMessage() {
        val tool = tool(
            """{"type":"object","properties":{"a":{"type":"string"}},"required":["a"],"additionalProperties":false}""",
        )
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - b: schema is false
              - c: schema is false
              - root: must not have additional properties

            Received arguments:
            {
              "a": "x",
              "b": 1,
              "c": 2
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"a":"x","b":1,"c":2}"""))
        }
    }

    @Test
    fun reportsEnumFailuresWithVerbatimMessage() {
        val tool = tool("""{"type":"object","properties":{"mode":{"type":"string","enum":["fast","slow"]}},"required":["mode"]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - mode: must be equal to one of the allowed values

            Received arguments:
            {
              "mode": "medium"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"mode":"medium"}"""))
        }
    }

    @Test
    fun reportsConstFailuresWithVerbatimMessage() {
        val tool = tool("""{"type":"object","properties":{"version":{"const":2}},"required":["version"]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - version: must be equal to constant

            Received arguments:
            {
              "version": 1
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"version":1}"""))
        }
    }

    @Test
    fun reportsAnyOfBranchErrorsThenSummary() {
        val tool = valueTool(
            """{"anyOf":[{"type":"string"},{"type":"object","properties":{"a":{"type":"number"}},"required":["a"]}]}""",
        )
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be string
              - value.a: must have required properties a
              - value: must match a schema in anyOf

            Received arguments:
            {
              "value": {}
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"value":{}}"""))
        }
    }

    @Test
    fun reportsOneOfFailureWhenMoreThanOneSchemaMatches() {
        val tool = valueTool("""{"oneOf":[{"type":"number"},{"type":"integer"}]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must match exactly one schema in oneOf

            Received arguments:
            {
              "value": 1
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"value":1}"""))
        }
    }

    @Test
    fun reportsAllOfWithoutSummaryError() {
        val tool = valueTool("""{"allOf":[{"type":"string"},{"type":"number"}]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be number

            Received arguments:
            {
              "value": true
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"value":true}"""))
        }
    }

    @Test
    fun reportsUnionTypeWithEitherMessage() {
        val tool = valueTool("""{"type":["boolean","number"]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be either boolean or number

            Received arguments:
            {
              "value": "yes"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"value":"yes"}"""))
        }
    }

    @Test
    fun reportsDeepArrayPaths() {
        val tool = tool(
            """{"type":"object","properties":{"a":{"type":"object","properties":{"b":{"type":"array","items":{"type":"number"}}}}},"required":["a"]}""",
        )
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - a.b.1: must be number

            Received arguments:
            {
              "a": {
                "b": [
                  0,
                  "x"
                ]
              }
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"a":{"b":[0,"x"]}}"""))
        }
    }

    @Test
    fun reportsWrongRootTypeAsRoot() {
        val tool = tool("""{"type":"array","items":{"type":"number"}}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - root: must be array

            Received arguments:
            {}
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = "{}"))
        }
    }

    @Test
    fun prettyPrintsNestedReceivedArguments() {
        val tool = tool("""{"type":"object","properties":{"v":{"type":"boolean"}},"required":["v"]}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - v: must be boolean

            Received arguments:
            {
              "v": 2,
              "list": [
                [
                  1,
                  2
                ],
                {
                  "x": null,
                  "y": []
                }
              ],
              "empty": {},
              "text": "a\nb"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(
                tool,
                call(arguments = """{"v":2,"list":[[1,2],{"x":null,"y":[]}],"empty":{},"text":"a\nb"}"""),
            )
        }
    }

    @Test
    fun escapesControlCharactersAndUnicodeLikeJsonStringify() {
        val tool = valueTool("""{"type":"number"}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be number

            Received arguments:
            {
              "value": "bad",
              "text": "say \"hi\"\nline\ttab\\back",
              "ctrl": "\u0001\u001f",
              "unicode": "中文é"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(
                tool,
                call(arguments = """{"value":"bad","text":"say \"hi\"\nline\ttab\\back","ctrl":"\u0001\u001f","unicode":"中文é"}"""),
            )
        }
    }

    @Test
    fun formatsNumbersInReceivedArgumentsLikeJavaScript() {
        val tool = valueTool("""{"type":"boolean"}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be boolean

            Received arguments:
            {
              "value": "TRUE",
              "nums": [
                0,
                0.1,
                1e+21,
                100000000000000000000,
                1e-7,
                123456789012345680,
                9007199254740992,
                0,
                10000000,
                9999999.5
              ]
            }
            """.trimIndent(),
        ) {
            validateToolArguments(
                tool,
                call(
                    arguments = """{"value":"TRUE","nums":[0,0.1,1e21,1e20,1e-7,123456789012345678,9007199254740993,-0.0,10000000,9999999.5]}""",
                ),
            )
        }
    }

    @Test
    fun capsCollectedErrorsAtEight() {
        val properties = (0 until 10).joinToString(",") { """"p$it":{"type":"boolean"}""" }
        val arguments = (0 until 10).joinToString(",") { """"p$it":2""" }
        val received = (0 until 10).joinToString(",\n") { """  "p$it": 2""" }
        val errors = (0 until 8).joinToString("\n") { """  - p$it: must be boolean""" }
        val tool = tool("""{"type":"object","properties":{$properties}}""")
        assertErrorMessage(
            "Validation failed for tool \"echo\":\n$errors\n\nReceived arguments:\n{\n$received\n}",
        ) {
            validateToolArguments(tool, call(arguments = "{$arguments}"))
        }
    }

    @Test
    fun rejectsNullForBooleanFalsePropertySchema() {
        val tool = tool("""{"type":"object","properties":{"v":false}}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - v: schema is false

            Received arguments:
            {
              "v": null
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"v":null}"""))
        }
    }

    @Test
    fun validatesItemsAgainstBooleanSchemas() {
        val tool = tool("""{"type":"object","properties":{"v":{"type":"array","items":false}}}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - v.0: schema is false
              - v.1: schema is false

            Received arguments:
            {
              "v": [
                1,
                2
              ]
            }
            """.trimIndent(),
        ) {
            validateToolArguments(tool, call(arguments = """{"v":[1,2]}"""))
        }
        assertResult(
            """{"v":[1,"x",null]}""",
            validateToolArguments(tool("""{"type":"object","properties":{"v":{"type":"array","items":true}}}"""), call(arguments = """{"v":[1,"x",null]}""")),
        )
    }

    @Test
    fun coercesNumbersToStringsUsingJavaScriptFormatting() {
        val tool = tool("""{"type":"object","properties":{"v":{"type":"array","items":{"type":"string"}}},"required":["v"]}""")
        val result = validateToolArguments(
            tool,
            call(arguments = """{"v":[0,0.1,0.30000000000000004,1e21,1e-7,9007199254740993,-42.5]}"""),
        )
        assertResult("""{"v":["0","0.1","0.30000000000000004","1e+21","1e-7","9007199254740992","-42.5"]}""", result)
    }

    @Test
    fun validateToolCallFindsToolByName() {
        val tool = valueTool("""{"type":"string"}""")
        assertResult(
            """{"value":"42"}""",
            validateToolCall(listOf(tool), call(arguments = """{"value":42}""")),
        )
        assertErrorMessage("Tool \"missing\" not found") {
            validateToolCall(listOf(tool), call(name = "missing", arguments = "{}"))
        }
    }

    @Test
    fun doesNotMutateOriginalArguments() {
        val tool = valueTool("""{"type":"number"}""")
        val call = call(arguments = """{"value":"42"}""")
        val result = validateToolArguments(tool, call)
        assertResult("""{"value":42}""", result)
        assertNotSame(call.arguments, result)
        assertEquals("42", call.arguments["value"]!!.jsonPrimitive.content)
        assertTrue(call.arguments["value"]!!.jsonPrimitive.isString)
    }

    @Test
    fun acceptsEmptySchemaAndExtraArguments() {
        val tool = tool("{}")
        assertResult(
            """{"anything":[1,2,{"x":null}]}""",
            validateToolArguments(tool, call(arguments = """{"anything":[1,2,{"x":null}]}""")),
        )
    }
}
