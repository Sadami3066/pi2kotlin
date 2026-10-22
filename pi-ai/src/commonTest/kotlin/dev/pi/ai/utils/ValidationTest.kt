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

    private data class GoldenCase(
        val name: String,
        val parameters: String,
        val arguments: String,
        val expectedResult: String?,
        val expectedError: String?,
    )

    private fun goldenCases(): List<GoldenCase> = listOf(
        GoldenCase(
            "minimum",
            """
                {"type":"object","properties":{"value":{"type":"number","minimum":5}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":3}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be >= 5

                Received arguments:
                {
                  "value": 3
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "minimum int",
            """
                {"type":"object","properties":{"value":{"type":"integer","minimum":1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":0}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be >= 1

                Received arguments:
                {
                  "value": 0
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "minimum float",
            """
                {"type":"object","properties":{"value":{"type":"number","minimum":1.5}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":1.25}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be >= 1.5

                Received arguments:
                {
                  "value": 1.25
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "maximum",
            """
                {"type":"object","properties":{"value":{"type":"number","maximum":5}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":6}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be <= 5

                Received arguments:
                {
                  "value": 6
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "exclusiveMinimum",
            """
                {"type":"object","properties":{"value":{"type":"number","exclusiveMinimum":5}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be > 5

                Received arguments:
                {
                  "value": 5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "exclusiveMaximum",
            """
                {"type":"object","properties":{"value":{"type":"number","exclusiveMaximum":5.5}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":5.5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be < 5.5

                Received arguments:
                {
                  "value": 5.5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "multipleOf",
            """
                {"type":"object","properties":{"value":{"type":"number","multipleOf":2}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":3}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be multiple of 2

                Received arguments:
                {
                  "value": 3
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "multipleOf decimal",
            """
                {"type":"object","properties":{"value":{"type":"number","multipleOf":0.1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":1.25}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be multiple of 0.1

                Received arguments:
                {
                  "value": 1.25
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "multipleOf zero",
            """
                {"type":"object","properties":{"value":{"type":"number","multipleOf":0}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be multiple of 0

                Received arguments:
                {
                  "value": 5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "multipleOf pass",
            """
                {"type":"object","properties":{"value":{"type":"number","multipleOf":0.01}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":19.99}
                """.trimIndent(),
            """
                {"value":19.99}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "minLength",
            """
                {"type":"object","properties":{"value":{"type":"string","minLength":3}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"ab"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have fewer than 3 characters

                Received arguments:
                {
                  "value": "ab"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "maxLength",
            """
                {"type":"object","properties":{"value":{"type":"string","maxLength":3}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"abcd"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have more than 3 characters

                Received arguments:
                {
                  "value": "abcd"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "minLength grapheme emoji",
            """
                {"type":"object","properties":{"value":{"type":"string","minLength":2}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"😀"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have fewer than 2 characters

                Received arguments:
                {
                  "value": "😀"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "maxLength grapheme family pass",
            """
                {"type":"object","properties":{"value":{"type":"string","maxLength":1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"👨‍👩‍👧‍👦"}
                """.trimIndent(),
            """
                {"value":"👨‍👩‍👧‍👦"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "maxLength grapheme flag pass",
            """
                {"type":"object","properties":{"value":{"type":"string","maxLength":1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"🇨🇳"}
                """.trimIndent(),
            """
                {"value":"🇨🇳"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "maxLength grapheme combining pass",
            """
                {"type":"object","properties":{"value":{"type":"string","maxLength":1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"é"}
                """.trimIndent(),
            """
                {"value":"é"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "pattern",
            """
                {"type":"object","properties":{"value":{"type":"string","pattern":"^a"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"b"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match pattern "^a"

                Received arguments:
                {
                  "value": "b"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "pattern unicode fail",
            """
                {"type":"object","properties":{"value":{"type":"string","pattern":"^\\p{L}+${'$'}"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"a1"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match pattern "^\p{L}+${'$'}"

                Received arguments:
                {
                  "value": "a1"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "pattern unicode pass",
            """
                {"type":"object","properties":{"value":{"type":"string","pattern":"^\\p{L}+${'$'}"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"abc"}
                """.trimIndent(),
            """
                {"value":"abc"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "format uuid fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"uuid"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"not-a-uuid"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "uuid"

                Received arguments:
                {
                  "value": "not-a-uuid"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format uuid pass",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"uuid"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"123e4567-e89b-12d3-a456-426614174000"}
                """.trimIndent(),
            """
                {"value":"123e4567-e89b-12d3-a456-426614174000"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "format email fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"email"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"nope"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "email"

                Received arguments:
                {
                  "value": "nope"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format email pass",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"email"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"a.b+c@example.co.uk"}
                """.trimIndent(),
            """
                {"value":"a.b+c@example.co.uk"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "format ipv4 fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"ipv4"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"999.1.1.1"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "ipv4"

                Received arguments:
                {
                  "value": "999.1.1.1"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format ipv6 fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"ipv6"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"1::2::3"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "ipv6"

                Received arguments:
                {
                  "value": "1::2::3"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format date fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"date"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"2021-02-29"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "date"

                Received arguments:
                {
                  "value": "2021-02-29"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format time fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"time"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"23:59:59"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "time"

                Received arguments:
                {
                  "value": "23:59:59"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format date-time fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"date-time"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"2020-12-12"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "date-time"

                Received arguments:
                {
                  "value": "2020-12-12"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format hostname fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"hostname"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"-bad-"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "hostname"

                Received arguments:
                {
                  "value": "-bad-"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format hostname trailing dot fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"hostname"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"example.com."}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "hostname"

                Received arguments:
                {
                  "value": "example.com."
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format json-pointer fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"json-pointer"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"abc"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "json-pointer"

                Received arguments:
                {
                  "value": "abc"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format uri fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"uri"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"not a uri"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "uri"

                Received arguments:
                {
                  "value": "not a uri"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format regex fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"regex"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"["}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "regex"

                Received arguments:
                {
                  "value": "["
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format duration fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"duration"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"P1D2H"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "duration"

                Received arguments:
                {
                  "value": "P1D2H"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format uri-template fail",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"uri-template"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"/users/{id"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must match format "uri-template"

                Received arguments:
                {
                  "value": "/users/{id"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "format unknown pass",
            """
                {"type":"object","properties":{"value":{"type":"string","format":"made-up"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"whatever"}
                """.trimIndent(),
            """
                {"value":"whatever"}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "string ordering",
            """
                {"type":"object","properties":{"value":{"type":"string","maxLength":2,"minLength":5,"format":"uuid","pattern":"^z"}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":"abc"}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have more than 2 characters
                  - value: must not have fewer than 5 characters
                  - value: must match format "uuid"
                  - value: must match pattern "^z"

                Received arguments:
                {
                  "value": "abc"
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "number ordering",
            """
                {"type":"object","properties":{"value":{"type":"number","exclusiveMaximum":1,"exclusiveMinimum":10,"maximum":2,"minimum":9,"multipleOf":3}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be < 1
                  - value: must be > 10
                  - value: must be <= 2
                  - value: must be >= 9
                  - value: must be multiple of 3

                Received arguments:
                {
                  "value": 5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array minItems",
            """
                {"type":"object","properties":{"value":{"type":"array","minItems":2}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[1]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have fewer than 2 items

                Received arguments:
                {
                  "value": [
                    1
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array maxItems",
            """
                {"type":"object","properties":{"value":{"type":"array","maxItems":1}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[1,2]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have more than 1 items

                Received arguments:
                {
                  "value": [
                    1,
                    2
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array uniqueItems numbers",
            """
                {"type":"object","properties":{"value":{"type":"array","uniqueItems":true}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[1,2,1,1]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have duplicate items

                Received arguments:
                {
                  "value": [
                    1,
                    2,
                    1,
                    1
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array uniqueItems 1 vs 1.0",
            """
                {"type":"object","properties":{"value":{"type":"array","uniqueItems":true}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[1,1]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have duplicate items

                Received arguments:
                {
                  "value": [
                    1,
                    1
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array uniqueItems objects",
            """
                {"type":"object","properties":{"value":{"type":"array","uniqueItems":true}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[{"a":1,"b":2},{"b":2,"a":1}]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must not have duplicate items

                Received arguments:
                {
                  "value": [
                    {
                      "a": 1,
                      "b": 2
                    },
                    {
                      "b": 2,
                      "a": 1
                    }
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array uniqueItems distinct",
            """
                {"type":"object","properties":{"value":{"type":"array","uniqueItems":true}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[true,1]}
                """.trimIndent(),
            """
                {"value":[true,1]}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "array contains",
            """
                {"type":"object","properties":{"value":{"type":"array","contains":{"type":"number"}}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":["a","b"]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must contain at least 1 valid item

                Received arguments:
                {
                  "value": [
                    "a",
                    "b"
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array contains empty",
            """
                {"type":"object","properties":{"value":{"type":"array","contains":{"type":"number"}}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":[]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must contain at least 1 valid item

                Received arguments:
                {
                  "value": []
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "array contains pass",
            """
                {"type":"object","properties":{"value":{"type":"array","contains":{"type":"number"}}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":["a",1]}
                """.trimIndent(),
            """
                {"value":["a",1]}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "array ordering",
            """
                {"type":"object","properties":{"value":{"type":"array","items":{"type":"string","pattern":"^x"},"contains":{"type":"boolean"},"maxItems":1,"minItems":3,"uniqueItems":true}},"required":["value"]}
                """.trimIndent(),
            """
                {"value":["a","a"]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must contain at least 1 valid item
                  - value.0: must match pattern "^x"
                  - value.1: must match pattern "^x"
                  - value: must not have more than 1 items
                  - value: must not have fewer than 3 items
                  - value: must not have duplicate items

                Received arguments:
                {
                  "value": [
                    "a",
                    "a"
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "nested property constraints",
            """
                {"type":"object","properties":{"outer":{"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":3}},"required":["count"],"additionalProperties":false}},"required":["outer"]}
                """.trimIndent(),
            """
                {"outer":{"count":5,"extra":true}}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - outer.extra: schema is false
                  - outer: must not have additional properties
                  - outer.count: must be <= 3

                Received arguments:
                {
                  "outer": {
                    "count": 5,
                    "extra": true
                  }
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref simple",
            """
                {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/positive"}},"required":["a"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"a":0}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - a: must be >= 1

                Received arguments:
                {
                  "a": 0
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref nested",
            """
                {"type":"object","properties":{"outer":{"type":"object","properties":{"inner":{"${'$'}ref":"#/${'$'}defs/positive"}},"required":["inner"]}},"required":["outer"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"outer":{"inner":-1}}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - outer.inner: must be >= 1

                Received arguments:
                {
                  "outer": {
                    "inner": -1
                  }
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref definitions",
            """
                {"type":"object","properties":{"a":{"${'$'}ref":"#/definitions/text"}},"required":["a"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}},"definitions":{"text":{"type":"string"}}}
                """.trimIndent(),
            """
                {"a":5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - a: must be string

                Received arguments:
                {
                  "a": 5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref property pointer",
            """
                {"type":"object","properties":{"a":{"${'$'}ref":"#/properties/b"},"b":{"type":"string"}},"required":["a"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"a":5}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - a: must be string

                Received arguments:
                {
                  "a": 5
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref sibling keywords",
            """
                {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/pos","maximum":10}},"required":["a"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"a":20}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - a: must be <= 10

                Received arguments:
                {
                  "a": 20
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref items",
            """
                {"type":"object","properties":{"value":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/text"}}},"required":["value"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"value":["a","bcd"]}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value.0: must not have fewer than 2 characters

                Received arguments:
                {
                  "value": [
                    "a",
                    "bcd"
                  ]
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref anyOf branch",
            """
                {"type":"object","properties":{"value":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/text"},{"${'$'}ref":"#/${'$'}defs/pos"}]}},"required":["value"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"value":true}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - value: must be string
                  - value: must be number
                  - value: must match a schema in anyOf

                Received arguments:
                {
                  "value": true
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref missing target",
            """
                {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/missing"}},"required":["a"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"a":1}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - a: schema is false

                Received arguments:
                {
                  "a": 1
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref recursive pass",
            """
                {"type":"object","properties":{"head":{"${'$'}ref":"#/${'$'}defs/node"}},"required":["head"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"head":{"value":1,"next":{"value":2,"next":null}}}
                """.trimIndent(),
            """
                {"head":{"value":1,"next":{"value":2,"next":null}}}
                """.trimIndent(),
            null,
        ),
        GoldenCase(
            "ref recursive fail",
            """
                {"type":"object","properties":{"head":{"${'$'}ref":"#/${'$'}defs/node"}},"required":["head"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"head":{"value":1,"next":{"value":"bad","next":null}}}
                """.trimIndent(),
            null,
            """
                Validation failed for tool "echo":
                  - head.next.value: must be number
                  - head.next: must be null
                  - head.next: must match a schema in anyOf

                Received arguments:
                {
                  "head": {
                    "value": 1,
                    "next": {
                      "value": "bad",
                      "next": null
                    }
                  }
                }
                """.trimIndent(),
        ),
        GoldenCase(
            "ref nullable union",
            """
                {"type":"object","properties":{"value":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/text"},{"type":"null"}]}},"required":["value"],"${'$'}defs":{"positive":{"type":"number","minimum":1},"text":{"type":"string","minLength":2},"pos":{"type":"number","minimum":0},"node":{"type":"object","properties":{"value":{"type":"number"},"next":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/node"},{"type":"null"}]}},"required":["value"],"additionalProperties":false}}}
                """.trimIndent(),
            """
                {"value":null}
                """.trimIndent(),
            """
                {"value":null}
                """.trimIndent(),
            null,
        ),
    )

    @Test
    fun matchesTypeBoxConversionGoldenMessagesForConstraintKeywords() {
        for (case in goldenCases()) {
            val tool = tool(case.parameters)
            val toolCall = call(arguments = case.arguments)
            if (case.expectedError != null) {
                val error = assertFailsWith<ToolArgumentValidationException>(case.name) {
                    validateToolArguments(tool, toolCall)
                }
                assertEquals(case.expectedError, error.message, case.name)
            } else {
                assertEquals(parse(case.expectedResult!!), validateToolArguments(tool, toolCall), case.name)
            }
        }
    }
    @Test
    fun coercesBeforeApplyingConstraintKeywords() {
        val numberTool = valueTool("""{"type":"number","minimum":5}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be >= 5

            Received arguments:
            {
              "value": "3"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(numberTool, call(arguments = """{"value":"3"}"""))
        }
        val multipleTool = valueTool("""{"type":"number","multipleOf":2}""")
        assertErrorMessage(
            """
            Validation failed for tool "echo":
              - value: must be multiple of 2

            Received arguments:
            {
              "value": "3"
            }
            """.trimIndent(),
        ) {
            validateToolArguments(multipleTool, call(arguments = """{"value":"3"}"""))
        }
    }

    @Test
    fun resolvesEscapedJsonPointerSegmentsInRefs() {
        val tool = tool(
            """
            {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/a~1b"},"c":{"${'$'}ref":"#/${'$'}defs/a~0b"},"d":{"${'$'}ref":"#/${'$'}defs/a%20b"}},"${'$'}defs":{"a/b":{"type":"string"},"a~b":{"type":"string"},"a b":{"type":"string"}}}
            """.trimIndent(),
        )
        val error = assertFailsWith<ToolArgumentValidationException> {
            validateToolArguments(tool, call(arguments = """{"a":1,"c":2,"d":3}"""))
        }
        assertEquals(
            listOf(
                "  - a: must be string",
                "  - c: must be string",
                "  - d: must be string",
            ).joinToString("\n"),
            error.message!!.lines().drop(1).take(3).joinToString("\n"),
        )
    }

    @Test
    fun guardsAgainstRecursiveRefCycles() {
        val tool = tool(
            """
            {"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/a"}},"${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/a"}}}
            """.trimIndent(),
        )
        val error = assertFailsWith<ToolArgumentValidationException> {
            validateToolArguments(tool, call(arguments = """{"a":1}"""))
        }
        assertTrue(error.message!!.contains("  - a: schema is false"))
    }

    @Test
    fun unknownFormatsAreIgnoredLikeTypeBoxRegistry() {
        val tool = valueTool("""{"type":"string","format":"not-registered"}""")
        assertResult("""{"value":"anything"}""", validateToolArguments(tool, call(arguments = """{"value":"anything"}""")))
    }
}
