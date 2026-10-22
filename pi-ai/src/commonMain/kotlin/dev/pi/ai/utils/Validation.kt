package dev.pi.ai.utils

import dev.pi.ai.JsonValue
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

class ToolArgumentValidationException(message: String) : Exception(message)

private const val MAX_SCHEMA_ERRORS = 8

private class SchemaError(
    val keyword: String,
    val instancePath: String,
    val message: String,
    val requiredProperties: List<String>? = null,
)

private class ErrorContext {
    val errors: MutableList<SchemaError> = mutableListOf()

    fun atCapacity(): Boolean = errors.size >= MAX_SCHEMA_ERRORS

    fun add(error: SchemaError) {
        if (!atCapacity()) errors.add(error)
    }
}

fun validateToolArguments(tool: Tool, toolCall: ToolCall): JsonObject {
    val argumentsError = toolCall.argumentsError
    if (!argumentsError.isNullOrEmpty()) {
        throw ToolArgumentValidationException("Invalid arguments for tool \"${toolCall.name}\": $argumentsError")
    }
    val parameters = tool.parameters
    val original = toolCall.arguments
    var args: JsonValue = jsonDeepCopy(original)
    args = normalizeOptionalNulls(args, parameters)
    args = coerceWithJsonSchema(args, parameters)
    val validated = args as? JsonObject ?: original
    val validator = SchemaValidator(parameters)
    if (validator.check(validated)) return validated
    val errors = validator.collectErrors(validated)
        .map { error -> "  - ${formatValidationPath(error)}: ${error.message}" }
        .joinToString("\n")
        .ifEmpty { "Unknown validation error" }
    val message = "Validation failed for tool \"${toolCall.name}\":\n$errors\n\nReceived arguments:\n${jsJsonPrettyPrint(original)}"
    throw ToolArgumentValidationException(message)
}

fun validateToolCall(tools: List<Tool>, toolCall: ToolCall): JsonObject {
    val tool = tools.find { it.name == toolCall.name }
        ?: throw ToolArgumentValidationException("Tool \"${toolCall.name}\" not found")
    return validateToolArguments(tool, toolCall)
}

private fun formatValidationPath(error: SchemaError): String {
    if (error.keyword == "required") {
        val requiredProperty = error.requiredProperties?.firstOrNull()
        if (requiredProperty != null) {
            val basePath = transformInstancePath(error.instancePath)
            return if (basePath.isNotEmpty()) "$basePath.$requiredProperty" else requiredProperty
        }
    }
    val path = transformInstancePath(error.instancePath)
    return path.ifEmpty { "root" }
}

private fun transformInstancePath(instancePath: String): String =
    instancePath.removePrefix("/").replace("/", ".")

private class SchemaValidator(private val root: JsonValue) {
    private val activeRefs = mutableSetOf<String>()

    fun check(value: JsonValue): Boolean = validate(root, value, "", ErrorContext())

    fun collectErrors(value: JsonValue): List<SchemaError> {
        val context = ErrorContext()
        validate(root, value, "", context)
        return context.errors
    }

    private fun validate(schema: JsonValue, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        if (context.atCapacity()) return false
        if (schema is JsonPrimitive && !schema.isString) {
            if (schema.content == "true") return true
            context.add(SchemaError("boolean", instancePath, "schema is false"))
            return false
        }
        if (schema !is JsonObject) return false

        var valid = true

        val type = schema["type"]
        if (isTypeDeclaration(type)) {
            val typeDeclaration = type!!
            if (!matchesTypeDeclaration(typeDeclaration, value)) {
                context.add(SchemaError("type", instancePath, typeErrorMessage(typeDeclaration)))
                valid = false
            }
        }

        if (value is JsonObject) {
            if (!validateObjectKeywords(schema, value, instancePath, context)) valid = false
        }

        if (value is JsonArray) {
            if (!validateArrayKeywords(schema, value, instancePath, context)) valid = false
        }

        if (value is JsonPrimitive && value.isString) {
            if (!validateStringKeywords(schema, value.content, instancePath, context)) valid = false
        }

        if (isJsonNumber(value)) {
            if (!validateNumberKeywords(schema, value, instancePath, context)) valid = false
        }

        val ref = schema["\$ref"]
        if (ref is JsonPrimitive && ref.isString) {
            if (!validateRef(ref.content, value, instancePath, context)) valid = false
        }

        if (schema.containsKey("const")) {
            if (!jsJsonEquals(schema["const"]!!, value)) {
                context.add(SchemaError("const", instancePath, "must be equal to constant"))
                valid = false
            }
        }

        val enum = schema["enum"]
        if (enum is JsonArray) {
            if (enum.none { option -> jsJsonEquals(option, value) }) {
                context.add(SchemaError("enum", instancePath, "must be equal to one of the allowed values"))
                valid = false
            }
        }

        val allOf = schema["allOf"]
        if (allOf is JsonArray && allOf.all(::isSchemaLike)) {
            if (!validateAllOf(allOf, value, instancePath, context)) valid = false
        }

        val anyOf = schema["anyOf"]
        if (anyOf is JsonArray && anyOf.all(::isSchemaLike)) {
            if (!validateAnyOf(anyOf, value, instancePath, context)) valid = false
        }

        val oneOf = schema["oneOf"]
        if (oneOf is JsonArray && oneOf.all(::isSchemaLike)) {
            if (!validateOneOf(oneOf, value, instancePath, context)) valid = false
        }

        return valid
    }

    private fun validateObjectKeywords(schema: JsonObject, value: JsonObject, instancePath: String, context: ErrorContext): Boolean {
        var valid = true

        val required = schema["required"]
        if (required is JsonArray && required.all { it is JsonPrimitive && it.isString }) {
            val missing = required.map { (it as JsonPrimitive).content }.filterNot { value.containsKey(it) }
            if (missing.isNotEmpty()) {
                context.add(
                    SchemaError(
                        keyword = "required",
                        instancePath = instancePath,
                        message = "must have required properties ${missing.joinToString(", ")}",
                        requiredProperties = missing,
                    ),
                )
                valid = false
            }
        }

        val additionalProperties = schema["additionalProperties"]
        if (additionalProperties != null && isSchemaLike(additionalProperties)) {
            if (!validateAdditionalProperties(schema, additionalProperties, value, instancePath, context)) valid = false
        }

        val properties = schema["properties"]
        if (properties is JsonObject && properties.values.all(::isSchemaLike)) {
            if (!validateProperties(properties, value, instancePath, context)) valid = false
        }

        return valid
    }

    private fun validateAdditionalProperties(
        schema: JsonObject,
        additionalProperties: JsonValue,
        value: JsonObject,
        instancePath: String,
        context: ErrorContext,
    ): Boolean {
        if (additionalProperties is JsonPrimitive && !additionalProperties.isString && additionalProperties.content == "true") return true
        if (additionalProperties is JsonObject && additionalProperties.isEmpty()) return true
        val definedKeys = (schema["properties"] as? JsonObject)?.keys ?: emptySet()
        val failed = mutableListOf<String>()
        for ((key, propertyValue) in value) {
            if (key in definedKeys) continue
            if (!validate(additionalProperties, propertyValue, "$instancePath/$key", context)) failed.add(key)
        }
        if (failed.isEmpty()) return true
        context.add(SchemaError("additionalProperties", instancePath, "must not have additional properties"))
        return false
    }

    private fun validateProperties(properties: JsonObject, value: JsonObject, instancePath: String, context: ErrorContext): Boolean {
        var valid = true
        for ((key, propertySchema) in properties) {
            if (!value.containsKey(key)) continue
            if (!validate(propertySchema, value[key]!!, "$instancePath/$key", context)) valid = false
        }
        return valid
    }

    private fun validateArrayKeywords(schema: JsonObject, value: JsonArray, instancePath: String, context: ErrorContext): Boolean {
        var valid = true

        val contains = schema["contains"]
        if (contains != null && isSchemaLike(contains) && !hasZeroMinContains(schema)) {
            if (!validateContains(contains, value, instancePath)) {
                context.add(SchemaError("contains", instancePath, "must contain at least 1 valid item"))
                valid = false
            }
        }

        val items = schema["items"]
        if (items is JsonArray && items.all(::isSchemaLike)) {
            for (index in items.indices) {
                if (value.size <= index) continue
                if (!validate(items[index], value[index], "$instancePath/$index", context)) valid = false
            }
        } else if (items != null && isSchemaLike(items)) {
            val itemSchema = items
            for (index in value.indices) {
                if (!validate(itemSchema, value[index], "$instancePath/$index", context)) valid = false
            }
        }

        val maxItems = numericKeyword(schema["maxItems"])
        if (maxItems != null && value.size > maxItems) {
            context.add(SchemaError("maxItems", instancePath, "must not have more than ${formatNumberLimit(maxItems)} items"))
            valid = false
        }

        val minItems = numericKeyword(schema["minItems"])
        if (minItems != null && value.size < minItems) {
            context.add(SchemaError("minItems", instancePath, "must not have fewer than ${formatNumberLimit(minItems)} items"))
            valid = false
        }

        val uniqueItems = schema["uniqueItems"]
        if (uniqueItems is JsonPrimitive && !uniqueItems.isString && uniqueItems.content == "true") {
            if (hasDuplicateItems(value)) {
                context.add(SchemaError("uniqueItems", instancePath, "must not have duplicate items"))
                valid = false
            }
        }

        return valid
    }

    private fun validateContains(contains: JsonValue, value: JsonArray, instancePath: String): Boolean {
        if (value.isEmpty()) return false
        for (index in value.indices) {
            if (validate(contains, value[index], "$instancePath/$index", ErrorContext())) return true
        }
        return false
    }

    private fun hasZeroMinContains(schema: JsonObject): Boolean {
        val minContains = numericKeyword(schema["minContains"]) ?: return false
        return minContains == 0.0
    }

    private fun hasDuplicateItems(value: JsonArray): Boolean {
        for (index in value.indices) {
            for (previous in 0 until index) {
                if (uniqueItemsEqual(value[previous], value[index])) return true
            }
        }
        return false
    }

    private fun validateStringKeywords(schema: JsonObject, text: String, instancePath: String, context: ErrorContext): Boolean {
        var valid = true
        val graphemeCount = graphemeCount(text)

        val maxLength = numericKeyword(schema["maxLength"])
        if (maxLength != null && graphemeCount > maxLength) {
            context.add(
                SchemaError("maxLength", instancePath, "must not have more than ${formatNumberLimit(maxLength)} characters"),
            )
            valid = false
        }

        val minLength = numericKeyword(schema["minLength"])
        if (minLength != null && graphemeCount < minLength) {
            context.add(
                SchemaError("minLength", instancePath, "must not have fewer than ${formatNumberLimit(minLength)} characters"),
            )
            valid = false
        }

        val format = schema["format"]
        if (format is JsonPrimitive && format.isString) {
            if (!formatMatches(format.content, text)) {
                context.add(SchemaError("format", instancePath, "must match format \"${format.content}\""))
                valid = false
            }
        }

        val pattern = schema["pattern"]
        if (pattern is JsonPrimitive && pattern.isString) {
            val regex = compilePattern(pattern.content)
            if (regex != null && !regex.containsMatchIn(text)) {
                context.add(SchemaError("pattern", instancePath, "must match pattern \"${pattern.content}\""))
                valid = false
            }
        }

        return valid
    }

    private fun validateNumberKeywords(schema: JsonObject, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        val number = (value as JsonPrimitive).content.toDouble()
        var valid = true

        val exclusiveMaximum = numericKeyword(schema["exclusiveMaximum"])
        if (exclusiveMaximum != null && !(number < exclusiveMaximum)) {
            context.add(
                SchemaError(
                    "exclusiveMaximum",
                    instancePath,
                    "must be < ${formatNumberLimit(exclusiveMaximum)}",
                ),
            )
            valid = false
        }

        val exclusiveMinimum = numericKeyword(schema["exclusiveMinimum"])
        if (exclusiveMinimum != null && !(number > exclusiveMinimum)) {
            context.add(
                SchemaError(
                    "exclusiveMinimum",
                    instancePath,
                    "must be > ${formatNumberLimit(exclusiveMinimum)}",
                ),
            )
            valid = false
        }

        val maximum = numericKeyword(schema["maximum"])
        if (maximum != null && !(number <= maximum)) {
            context.add(SchemaError("maximum", instancePath, "must be <= ${formatNumberLimit(maximum)}"))
            valid = false
        }

        val minimum = numericKeyword(schema["minimum"])
        if (minimum != null && !(number >= minimum)) {
            context.add(SchemaError("minimum", instancePath, "must be >= ${formatNumberLimit(minimum)}"))
            valid = false
        }

        val multipleOf = numericKeyword(schema["multipleOf"])
        if (multipleOf != null && !isMultipleOf(number, multipleOf)) {
            context.add(SchemaError("multipleOf", instancePath, "must be multiple of ${formatNumberLimit(multipleOf)}"))
            valid = false
        }

        return valid
    }

    private fun validateRef(ref: String, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        val cycleKey = "$ref@$instancePath"
        if (!activeRefs.add(cycleKey)) {
            context.add(SchemaError("boolean", instancePath, "schema is false"))
            return false
        }
        try {
            val target = resolveLocalRef(ref)
            if (target == null) {
                context.add(SchemaError("boolean", instancePath, "schema is false"))
                return false
            }
            val branch = ErrorContext()
            val result = validate(target, value, instancePath, branch)
            if (!result) branch.errors.forEach(context::add)
            return result
        } finally {
            activeRefs.remove(cycleKey)
        }
    }

    private fun resolveLocalRef(ref: String): JsonValue? {
        if (!ref.startsWith("#")) return null
        val fragment = ref.substring(1)
        if (fragment.isEmpty()) return root
        if (!fragment.startsWith("/")) return null
        val decoded = decodeUriFragment(fragment) ?: return null
        var current: JsonValue = root
        for (rawSegment in decoded.split("/").drop(1)) {
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> segment.toIntOrNull()?.takeIf { it in current.indices }?.let { current[it] } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun validateAllOf(schemas: JsonArray, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        val failed = mutableListOf<ErrorContext>()
        var allValid = true
        for (schema in schemas) {
            val branch = ErrorContext()
            if (!validate(schema, value, instancePath, branch)) {
                failed.add(branch)
                allValid = false
            }
        }
        if (!allValid) failed.forEach { branch -> branch.errors.forEach(context::add) }
        return allValid
    }

    private fun validateAnyOf(schemas: JsonArray, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        val failed = mutableListOf<ErrorContext>()
        var passed = 0
        for (schema in schemas) {
            val branch = ErrorContext()
            if (validate(schema, value, instancePath, branch)) passed++ else failed.add(branch)
        }
        if (passed > 0) return true
        failed.forEach { branch -> branch.errors.forEach(context::add) }
        context.add(SchemaError("anyOf", instancePath, "must match a schema in anyOf"))
        return false
    }

    private fun validateOneOf(schemas: JsonArray, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
        val failed = mutableListOf<ErrorContext>()
        var passed = 0
        for (schema in schemas) {
            val branch = ErrorContext()
            if (validate(schema, value, instancePath, branch)) passed++ else failed.add(branch)
        }
        if (passed == 1) return true
        if (passed == 0) failed.forEach { branch -> branch.errors.forEach(context::add) }
        context.add(SchemaError("oneOf", instancePath, "must match exactly one schema in oneOf"))
        return false
    }
}

private fun isSchemaLike(value: JsonValue?): Boolean = when (value) {
    is JsonObject -> true
    is JsonPrimitive -> !value.isString && (value.content == "true" || value.content == "false")
    else -> false
}

private fun isTypeDeclaration(type: JsonValue?): Boolean = when (type) {
    is JsonPrimitive -> type.isString
    is JsonArray -> type.all { it is JsonPrimitive && it.isString }
    else -> false
}

private fun matchesTypeDeclaration(type: JsonValue, value: JsonValue): Boolean = when (type) {
    is JsonArray -> type.any { matchesTypeName((it as JsonPrimitive).content, value) }
    else -> matchesTypeName((type as JsonPrimitive).content, value)
}

private fun matchesTypeName(type: String, value: JsonValue): Boolean = when (type) {
    "number" -> isJsonNumber(value)
    "integer" -> isJsonInteger(value)
    "boolean" -> isJsonBoolean(value)
    "string" -> value is JsonPrimitive && value.isString
    "null" -> value is JsonNull
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    else -> true
}

private fun isJsonNumber(value: JsonValue): Boolean =
    value is JsonPrimitive && value !is JsonNull && !value.isString && value.content.toDoubleOrNull() != null

private fun isJsonInteger(value: JsonValue): Boolean {
    if (!isJsonNumber(value)) return false
    val number = (value as JsonPrimitive).content.toDouble()
    return number.isFinite() && number % 1.0 == 0.0
}

private fun isJsonBoolean(value: JsonValue): Boolean =
    value is JsonPrimitive && value !is JsonNull && !value.isString && (value.content == "true" || value.content == "false")

private fun typeErrorMessage(type: JsonValue): String = when (type) {
    is JsonArray -> "must be either " + type.joinToString(" or ") { (it as JsonPrimitive).content }
    else -> "must be " + (type as JsonPrimitive).content
}

private fun numericKeyword(value: JsonValue?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    return primitive.content.toDoubleOrNull()
}

private fun formatNumberLimit(limit: Double): String = jsNumberToString(limit)

private fun isMultipleOf(dividend: Double, divisor: Double): Boolean {
    if (dividend.isFinite() && dividend % 1.0 == 0.0 && (1.0 / divisor) % 1.0 == 0.0) return true
    val mod = dividend % divisor
    return minOf(kotlin.math.abs(mod), kotlin.math.abs(mod - divisor), kotlin.math.abs(mod + divisor)) < 1e-10
}

private fun uniqueItemsEqual(a: JsonValue, b: JsonValue): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString != b.isString -> false
        a.isString -> a.content == b.content
        else -> {
            val left = a.content.toDoubleOrNull()
            val right = b.content.toDoubleOrNull()
            if (left != null && right != null) left.toRawBits() == right.toRawBits() else a.content == b.content
        }
    }

    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { index -> uniqueItemsEqual(a[index], b[index]) }
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { key -> uniqueItemsEqual(a[key]!!, b[key]!!) }
    else -> false
}

private fun decodeUriFragment(fragment: String): String? {
    if (!fragment.contains('%')) return fragment
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < fragment.length) {
        val char = fragment[index]
        if (char == '%') {
            if (index + 2 >= fragment.length) return null
            val value = fragment.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes.add(value.toByte())
            index += 3
        } else {
            val encoded = char.toString().encodeToByteArray()
            encoded.forEach { bytes.add(it) }
            index += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun graphemeCount(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        index = nextGraphemeClusterIndex(value, index)
        count++
    }
    return count
}

private fun nextGraphemeClusterIndex(value: String, clusterStart: Int): Int {
    val startCodePoint = codePointAt(value, clusterStart)
    var clusterEnd = clusterStart + codePointLength(startCodePoint)
    clusterEnd = consumeGraphemeModifiers(value, clusterEnd)
    while (clusterEnd < value.length - 1 && codePointAt(value, clusterEnd) == 0x200D) {
        val nextCodePoint = codePointAt(value, clusterEnd + 1)
        clusterEnd += 1 + codePointLength(nextCodePoint)
        clusterEnd = consumeGraphemeModifiers(value, clusterEnd)
    }
    if (isRegionalIndicator(startCodePoint) && clusterEnd < value.length && isRegionalIndicator(codePointAt(value, clusterEnd))) {
        clusterEnd += codePointLength(codePointAt(value, clusterEnd))
    }
    return clusterEnd
}

private fun consumeGraphemeModifiers(value: String, start: Int): Int {
    var index = start
    while (index < value.length) {
        val codePoint = codePointAt(value, index)
        if (isCombiningMark(codePoint) || isVariationSelector(codePoint)) {
            index += codePointLength(codePoint)
        } else {
            break
        }
    }
    return index
}

private fun codePointAt(value: String, index: Int): Int {
    val char = value[index]
    if (char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
        val high = char.code - 0xD800
        val low = value[index + 1].code - 0xDC00
        return 0x10000 + (high shl 10) + low
    }
    return char.code
}

private fun codePointLength(codePoint: Int): Int = if (codePoint > 0xFFFF) 2 else 1

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

private fun isVariationSelector(codePoint: Int): Boolean = codePoint in 0xFE00..0xFE0F

private fun isZeroWidthJoiner(codePoint: Int): Boolean = codePoint == 0x200D

private fun isCombiningMark(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F ||
        codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF ||
        codePoint in 0xFE20..0xFE2F

private fun compilePattern(pattern: String): Regex? = try {
    Regex(pattern)
} catch (error: Exception) {
    null
}

private fun formatMatches(format: String, value: String): Boolean = when (format) {
    "date-time" -> isDateTimeFormat(value)
    "date" -> isDateFormat(value)
    "time" -> isTimeFormat(value)
    "duration" -> DURATION_PATTERN.matches(value)
    "email" -> EMAIL_PATTERN.matches(value)
    "idn-email" -> isIdnEmailFormat(value)
    "hostname", "idn-hostname" -> isHostnameFormat(value)
    "ipv4" -> IPV4_PATTERN.matches(value)
    "ipv6" -> IPV6_PATTERN.matches(value)
    "iri" -> isIriFormat(value)
    "iri-reference" -> isIriReferenceFormat(value)
    "uri" -> URI_PATTERN.matches(value)
    "uri-reference" -> URI_REFERENCE_PATTERN.matches(value)
    "url" -> isUrlFormat(value)
    "uuid" -> UUID_PATTERN.matches(value)
    "json-pointer" -> JSON_POINTER_PATTERN.matches(value)
    "json-pointer-uri-fragment" -> JSON_POINTER_URI_FRAGMENT_PATTERN.matches(value)
    "relative-json-pointer" -> RELATIVE_JSON_POINTER_PATTERN.matches(value)
    "regex" -> isValidRegexFormat(value)
    "uri-template" -> URI_TEMPLATE_PATTERN.matches(value)
    else -> true
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}${'$'}", RegexOption.IGNORE_CASE)

private val IPV4_PATTERN = Regex("""^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)${'$'}""")

private val IPV6_PATTERN = Regex(
    """^(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:)?[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)${'$'}""",
    RegexOption.IGNORE_CASE,
)

private val EMAIL_PATTERN = Regex(
    """^(?:[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+)*|"(?:[^"\\]|\\[\x20-\x7e])*")@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*|\[(?:IPv6:[a-f0-9:]+|(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3})\])${'$'}""",
    RegexOption.IGNORE_CASE,
)

private val DURATION_PATTERN = Regex("""^P((\d+Y(\d+M(\d+D)?)?|\d+M(\d+D)?|\d+D)(T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S))?|T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S)|\d+W)${'$'}""")

private val JSON_POINTER_PATTERN = Regex("""^(?:\/(?:[^~/]|~0|~1)*)*${'$'}""")

private val JSON_POINTER_URI_FRAGMENT_PATTERN = Regex(
    """^#(?:\/(?:[a-z0-9_\-.!${'$'}&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*${'$'}""",
    RegexOption.IGNORE_CASE,
)

private val RELATIVE_JSON_POINTER_PATTERN = Regex("""^(?:0|[1-9][0-9]*)(?:#|(?:\/(?:[^~/]|~0|~1)*)*)${'$'}""")

private val URI_TEMPLATE_PATTERN = Regex(
    """^(?:(?:[^\x00-\x20"<>%\\^`{|}\x7f]|%[0-9a-f]{2})|\{[+#./;?&=,!@|]?(?:[a-z0-9_]|%[0-9a-f]{2})+(?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*(?::[1-9]\d{0,3}|\*)?(?:,(?:[a-z0-9_]|%[0-9a-f]{2})+(?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*(?::[1-9]\d{0,3}|\*)?)*\})*${'$'}""",
    RegexOption.IGNORE_CASE,
)

private fun isDateFormat(value: String): Boolean {
    val match = DATE_PATTERN.find(value) ?: return false
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val days = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val maxDay = if (month == 2 && isLeapYear(year)) 29 else if (month in 1..12) days[month] else 0
    return month in 1..12 && day in 1..maxDay
}

private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private val DATE_PATTERN = Regex("""^(\d\d\d\d)-(\d\d)-(\d\d)${'$'}""")

private val TIME_PATTERN = Regex("""^(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:([Zz])|([+-])(\d\d):(\d\d))?${'$'}""")

private fun isTimeFormat(value: String): Boolean {
    val match = TIME_PATTERN.find(value) ?: return false
    if (match.groupValues[4].isEmpty() && match.groupValues[5].isEmpty()) return false
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    val second = match.groupValues[3].toInt()
    if (hour > 23 || minute > 59 || second > 60) return false
    if (match.groupValues[5].isNotEmpty()) {
        val timezoneHour = match.groupValues[6].toInt()
        val timezoneMinute = match.groupValues[7].toInt()
        if (timezoneHour > 23 || timezoneMinute > 59) return false
    }
    if (second < 60) return true
    val timezoneSign = if (match.groupValues[5] == "-") -1 else 1
    val timezoneHour = match.groupValues[6].ifEmpty { "0" }.toInt()
    val timezoneMinute = match.groupValues[7].ifEmpty { "0" }.toInt()
    val totalUtcMinutes = hour * 60 + minute - timezoneSign * (timezoneHour * 60 + timezoneMinute)
    return ((totalUtcMinutes % 1440) + 1440) % 1440 == 1439
}

private fun isDateTimeFormat(value: String): Boolean {
    val parts = value.split(Regex("[Tt]"))
    return parts.size == 2 && isDateFormat(parts[0]) && isTimeFormat(parts[1])
}

private fun isHostnameFormat(value: String): Boolean {
    if (value.isEmpty() || value.length > 253) return false
    if (value.endsWith(".")) return false
    val labels = value.split(".")
    for (label in labels) {
        if (label.isEmpty() || label.length > 63) return false
        if (label.startsWith("-") || label.endsWith("-")) return false
        if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
    }
    return true
}

private fun isUrlFormat(value: String): Boolean {
    if (!URI_PATTERN.matches(value)) return false
    val scheme = value.substringBefore(':', "").lowercase()
    if (scheme.isEmpty()) return false
    if (scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss" || scheme == "ftp") {
        val rest = value.substringAfter(':', "")
        return rest.startsWith("//") && rest.substring(2).substringBefore('/').substringBefore('?').isNotEmpty()
    }
    return true
}

private fun isValidRegexFormat(value: String): Boolean = try {
    Regex(value)
    true
} catch (error: Exception) {
    false
}

private val URI_PATTERN = Regex("""^[a-z][a-z0-9+\-.]*:(?:\/\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!${'$'}&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!${'$'}&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?(?:\?(?:[-a-z0-9._~!${'$'}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[-a-z0-9._~!${'$'}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?${'$'}""" , RegexOption.IGNORE_CASE)

private val URI_REFERENCE_PATTERN = Regex("""^(?:[a-z][a-z0-9+\-.]*:(?:\/\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!${'$'}&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!${'$'}&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:\/\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!${'$'}&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!${'$'}&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!${'$'}&'()*+,;=@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!${'$'}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?)(?:\?(?:[-a-z0-9._~!${'$'}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[-a-z0-9._~!${'$'}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?${'$'}""" , RegexOption.IGNORE_CASE)

private val IRI_INVALID_CHARS = Regex("""[\x00-\x20<>\^`{|}\\]""")
private val INVALID_PERCENT_ENCODING = Regex("""%(?![0-9a-fA-F]{2})""")
private val MALFORMED_IRI_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+\-.]*//""")
private val IRI_SCHEME_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9+\-.]*:""")

private fun isIriFormat(value: String): Boolean {
    if (IRI_INVALID_CHARS.containsMatchIn(value) || INVALID_PERCENT_ENCODING.containsMatchIn(value)) return false
    if (URI_PATTERN.matches(value)) return true
    return IRI_SCHEME_PATTERN.containsMatchIn(value)
}

private fun isIriReferenceFormat(value: String): Boolean {
    if (IRI_INVALID_CHARS.containsMatchIn(value) || MALFORMED_IRI_SCHEME.containsMatchIn(value)) return false
    if (URI_REFERENCE_PATTERN.matches(value)) return true
    return !value.startsWith("//") || value.length > 2
}

private fun isIdnEmailFormat(value: String): Boolean {
    if (EMAIL_PATTERN.matches(value)) return true
    val separator = value.lastIndexOf('@')
    if (separator <= 0 || separator == value.length - 1) return false
    val local = value.substring(0, separator)
    val domain = value.substring(separator + 1)
    if (local.any { it.isWhitespace() || it.code < 0x21 }) return false
    return isHostnameFormat(domain)
}

private fun normalizeOptionalNulls(value: JsonValue, schema: JsonValue): JsonValue {
    if (value is JsonArray) {
        val items = (schema as? JsonObject)?.get("items")
        return when {
            items is JsonArray -> JsonArray(
                value.mapIndexed { index, element ->
                    val itemSchema = items.getOrNull(index)
                    if (itemSchema == null) element else normalizeOptionalNulls(element, itemSchema)
                },
            )
            items is JsonObject -> JsonArray(value.map { element -> normalizeOptionalNulls(element, items) })
            items is JsonPrimitive && !items.isString && items.content == "true" ->
                JsonArray(value.map { element -> normalizeOptionalNulls(element, items) })
            else -> value
        }
    }
    if (value !is JsonObject) return value
    val schemaObject = schema as? JsonObject ?: return value
    val properties = schemaObject["properties"] as? JsonObject ?: return value
    val required = (schemaObject["required"] as? JsonArray)
        ?.filterIsInstance<JsonPrimitive>()
        ?.filter { it.isString }
        ?.map { it.content }
        ?.toSet()
        ?: emptySet()
    var changed = false
    val result = LinkedHashMap<String, JsonValue>()
    for ((key, propertyValue) in value) {
        val propertySchema = properties[key]
        if (propertySchema == null || !properties.containsKey(key)) {
            result[key] = propertyValue
            continue
        }
        val optionalNull = propertyValue is JsonNull &&
            key !in required &&
            !hasStringRef(propertySchema) &&
            checkSchemaStatus(propertySchema, JsonNull) == false
        if (optionalNull) {
            changed = true
            continue
        }
        val normalized = normalizeOptionalNulls(propertyValue, propertySchema)
        if (normalized != propertyValue) changed = true
        result[key] = normalized
    }
    return if (changed) JsonObject(result) else value
}

private fun hasStringRef(schema: JsonValue): Boolean {
    val ref = (schema as? JsonObject)?.get("\$ref") ?: return false
    return ref is JsonPrimitive && ref.isString
}

private fun checkSchemaStatus(schema: JsonValue, value: JsonValue): Boolean? = when {
    schema is JsonObject -> SchemaValidator(schema).check(value)
    else -> null
}

private fun coerceWithJsonSchema(value: JsonValue, schema: JsonValue): JsonValue {
    if (schema !is JsonObject) return value
    var nextValue = value

    val allOf = schema["allOf"]
    if (allOf is JsonArray) {
        for (nested in allOf) nextValue = coerceWithJsonSchema(nextValue, nested)
    }

    val anyOf = schema["anyOf"]
    if (anyOf is JsonArray) nextValue = coerceWithUnionSchema(nextValue, anyOf)

    val oneOf = schema["oneOf"]
    if (oneOf is JsonArray) nextValue = coerceWithUnionSchema(nextValue, oneOf)

    val schemaTypes = getSchemaTypes(schema)
    val matchesUnionMember = schemaTypes.size > 1 && schemaTypes.any { matchesTypeName(it, nextValue) }
    if (schemaTypes.isNotEmpty() && !matchesUnionMember) {
        for (schemaType in schemaTypes) {
            val candidate = coercePrimitiveByType(nextValue, schemaType)
            if (candidate != nextValue) {
                nextValue = candidate
                break
            }
        }
    }

    if ("object" in schemaTypes && nextValue is JsonObject) {
        nextValue = applySchemaObjectCoercion(nextValue, schema)
    }

    if ("array" in schemaTypes && nextValue is JsonArray) {
        nextValue = applySchemaArrayCoercion(nextValue, schema)
    }

    return nextValue
}

private fun getSchemaTypes(schema: JsonObject): List<String> = when (val type = schema["type"]) {
    is JsonPrimitive -> if (type.isString) listOf(type.content) else emptyList()
    is JsonArray -> type.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

private fun coerceWithUnionSchema(value: JsonValue, schemas: JsonArray): JsonValue {
    for (schema in schemas) {
        if (checkSchemaStatus(schema, value) == true) return value
    }
    for (schema in schemas) {
        val coerced = coerceWithJsonSchema(value, schema)
        if (checkSchemaStatus(schema, coerced) == true) return coerced
    }
    return value
}

private fun coercePrimitiveByType(value: JsonValue, type: String): JsonValue = when (type) {
    "number" -> {
        when {
            value is JsonNull -> jsonNumber(0.0)
            value is JsonPrimitive && value.isString && value.content.trim().isNotEmpty() -> {
                val parsed = parseJsNumber(value.content)
                if (parsed != null && parsed.isFinite()) jsonNumber(parsed) else value
            }
            isJsonBoolean(value) -> jsonNumber(if ((value as JsonPrimitive).content == "true") 1.0 else 0.0)
            else -> value
        }
    }
    "integer" -> {
        when {
            value is JsonNull -> jsonNumber(0.0)
            value is JsonPrimitive && value.isString && value.content.trim().isNotEmpty() -> {
                val parsed = parseJsNumber(value.content)
                if (parsed != null && parsed.isFinite() && parsed % 1.0 == 0.0) jsonNumber(parsed) else value
            }
            isJsonBoolean(value) -> jsonNumber(if ((value as JsonPrimitive).content == "true") 1.0 else 0.0)
            else -> value
        }
    }
    "boolean" -> {
        when {
            value is JsonNull -> JsonPrimitive(false)
            value is JsonPrimitive && value.isString -> when (value.content) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> value
            }
            isJsonNumber(value) -> {
                val number = (value as JsonPrimitive).content.toDouble()
                when (number) {
                    1.0 -> JsonPrimitive(true)
                    0.0 -> JsonPrimitive(false)
                    else -> value
                }
            }
            else -> value
        }
    }
    "string" -> {
        when {
            value is JsonNull -> JsonPrimitive("")
            value is JsonPrimitive && isJsonBoolean(value) -> JsonPrimitive(value.content)
            isJsonNumber(value) -> JsonPrimitive(jsNumberToString((value as JsonPrimitive).content.toDouble()))
            else -> value
        }
    }
    "null" -> {
        when {
            value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
            isJsonNumber(value) && (value as JsonPrimitive).content.toDouble() == 0.0 -> JsonNull
            isJsonBoolean(value) && (value as JsonPrimitive).content == "false" -> JsonNull
            else -> value
        }
    }
    else -> value
}

private fun applySchemaObjectCoercion(value: JsonObject, schema: JsonObject): JsonObject {
    val properties = schema["properties"] as? JsonObject
    val definedKeys = properties?.keys ?: emptySet()
    val additionalProperties = schema["additionalProperties"] as? JsonObject
    var changed = false
    val result = LinkedHashMap<String, JsonValue>()
    for ((key, propertyValue) in value) {
        val propertySchema = properties?.get(key)
        val coerced = when {
            propertySchema != null -> coerceWithJsonSchema(propertyValue, propertySchema)
            additionalProperties != null && key !in definedKeys -> coerceWithJsonSchema(propertyValue, additionalProperties)
            else -> propertyValue
        }
        if (coerced != propertyValue) changed = true
        result[key] = coerced
    }
    return if (changed) JsonObject(result) else value
}

private fun applySchemaArrayCoercion(value: JsonArray, schema: JsonObject): JsonArray {
    val items = schema["items"]
    if (items is JsonArray) {
        return JsonArray(
            value.mapIndexed { index, element ->
                val itemSchema = items.getOrNull(index)
                if (itemSchema == null) element else coerceWithJsonSchema(element, itemSchema)
            },
        )
    }
    if (items is JsonObject) {
        return JsonArray(value.map { element -> coerceWithJsonSchema(element, items) })
    }
    return value
}

private fun parseJsNumber(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    var sign = 1.0
    var body = trimmed
    if (body.startsWith("+")) {
        body = body.substring(1)
    } else if (body.startsWith("-")) {
        sign = -1.0
        body = body.substring(1)
    }
    if (body == "Infinity") return sign * Double.POSITIVE_INFINITY
    if (body.length > 2 && (body.startsWith("0x") || body.startsWith("0X"))) {
        return body.substring(2).toLongOrNull(16)?.let { sign * it.toDouble() }
    }
    if (body.length > 2 && (body.startsWith("0b") || body.startsWith("0B"))) {
        return body.substring(2).toLongOrNull(2)?.let { sign * it.toDouble() }
    }
    if (body.length > 2 && (body.startsWith("0o") || body.startsWith("0O"))) {
        return body.substring(2).toLongOrNull(8)?.let { sign * it.toDouble() }
    }
    return trimmed.toDoubleOrNull()
}

private fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == Double.POSITIVE_INFINITY) return "Infinity"
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (value == 0.0) return "0"
    val negative = value < 0
    val abs = if (negative) -value else value
    val prefix = if (negative) "-" else ""
    if (abs == Double.MIN_VALUE) return prefix + "5e-324"
    val repr = abs.toString()
    if (abs >= 1e21 || abs < 1e-6) {
        val exponentIndex = repr.indexOf('E')
        val rawMantissa = if (exponentIndex >= 0) repr.substring(0, exponentIndex) else repr
        val exponent = if (exponentIndex >= 0) repr.substring(exponentIndex + 1).toInt() else 0
        val mantissa = stripTrailingZeros(rawMantissa)
        return prefix + mantissa + "e" + (if (exponent >= 0) "+" else "") + exponent
    }
    return prefix + expandExponent(repr)
}

private fun stripTrailingZeros(raw: String): String {
    if (!raw.contains('.')) return raw
    var end = raw.length
    while (end > 0 && raw[end - 1] == '0') end--
    if (end > 0 && raw[end - 1] == '.') end--
    return raw.substring(0, end)
}

private fun expandExponent(repr: String): String {
    val exponentIndex = repr.indexOf('E')
    if (exponentIndex < 0) return stripTrailingZeros(repr)
    val rawMantissa = stripTrailingZeros(repr.substring(0, exponentIndex))
    val exponent = repr.substring(exponentIndex + 1).toInt()
    val dotIndex = rawMantissa.indexOf('.')
    var digits = rawMantissa.replace(".", "")
    var decimalPosition = (if (dotIndex >= 0) dotIndex else rawMantissa.length) + exponent
    while (digits.length > 1 && digits.startsWith("0")) {
        digits = digits.substring(1)
        decimalPosition--
    }
    return when {
        decimalPosition <= 0 -> "0." + "0".repeat(-decimalPosition) + digits
        decimalPosition >= digits.length -> digits + "0".repeat(decimalPosition - digits.length)
        else -> digits.substring(0, decimalPosition) + "." + digits.substring(decimalPosition)
    }
}

private fun jsonNumber(value: Double): JsonPrimitive = JsonUnquotedLiteral(jsNumberToString(value))

internal fun jsonDeepCopy(value: JsonValue): JsonValue = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, nested) -> jsonDeepCopy(nested) })
    is JsonArray -> JsonArray(value.map(::jsonDeepCopy))
    else -> value
}

private fun jsJsonEquals(a: JsonValue, b: JsonValue): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString != b.isString -> false
        a.isString -> a.content == b.content
        else -> {
            val left = a.content.toDoubleOrNull()
            val right = b.content.toDoubleOrNull()
            if (left != null && right != null) left == right else a.content == b.content
        }
    }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { index -> jsJsonEquals(a[index], b[index]) }
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { key -> jsJsonEquals(a[key]!!, b[key]!!) }
    else -> false
}

internal fun jsJsonStringify(element: JsonElement): String = buildString { appendCompact(this, element) }

private fun jsJsonPrettyPrint(element: JsonElement): String = buildString { appendPretty(this, element, 0) }

private fun appendCompact(builder: StringBuilder, element: JsonElement) {
    when (element) {
        is JsonNull -> builder.append("null")
        is JsonPrimitive -> appendPrimitive(builder, element)
        is JsonArray -> {
            builder.append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) builder.append(',')
                appendCompact(builder, item)
            }
            builder.append(']')
        }
        is JsonObject -> {
            builder.append('{')
            var first = true
            for ((key, value) in element) {
                if (!first) builder.append(',')
                first = false
                appendJsonString(builder, key)
                builder.append(':')
                appendCompact(builder, value)
            }
            builder.append('}')
        }
    }
}

private fun appendPretty(builder: StringBuilder, element: JsonElement, level: Int) {
    when (element) {
        is JsonNull -> builder.append("null")
        is JsonPrimitive -> appendPrimitive(builder, element)
        is JsonArray -> {
            if (element.isEmpty()) {
                builder.append("[]")
                return
            }
            builder.append("[\n")
            element.forEachIndexed { index, item ->
                builder.append("  ".repeat(level + 1))
                appendPretty(builder, item, level + 1)
                if (index < element.size - 1) builder.append(',')
                builder.append('\n')
            }
            builder.append("  ".repeat(level)).append(']')
        }
        is JsonObject -> {
            if (element.isEmpty()) {
                builder.append("{}")
                return
            }
            builder.append("{\n")
            var index = 0
            for ((key, value) in element) {
                builder.append("  ".repeat(level + 1))
                appendJsonString(builder, key)
                builder.append(": ")
                appendPretty(builder, value, level + 1)
                if (index < element.size - 1) builder.append(',')
                builder.append('\n')
                index++
            }
            builder.append("  ".repeat(level)).append('}')
        }
    }
}

private fun appendPrimitive(builder: StringBuilder, element: JsonPrimitive) {
    if (element is JsonNull) {
        builder.append("null")
        return
    }
    if (element.isString) {
        appendJsonString(builder, element.content)
        return
    }
    val content = element.content
    if (content == "true" || content == "false") {
        builder.append(content)
        return
    }
    val number = content.toDoubleOrNull()
    if (number != null) {
        builder.append(if (number.isFinite()) jsNumberToString(number) else "null")
        return
    }
    builder.append(content)
}

private fun appendJsonString(builder: StringBuilder, value: String) {
    builder.append('"')
    for (char in value) {
        when (char) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> if (char < ' ') builder.append("\\u").append(char.code.toString(16).padStart(4, '0')) else builder.append(char)
        }
    }
    builder.append('"')
}
