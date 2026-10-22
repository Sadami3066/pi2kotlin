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
    if (validateSchema(parameters, validated, "", ErrorContext())) return validated
    val errorContext = ErrorContext()
    validateSchema(parameters, validated, "", errorContext)
    val errors = errorContext.errors
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

private fun validateSchema(schema: JsonValue, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
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
        if (!validateSchema(additionalProperties, propertyValue, "$instancePath/$key", context)) failed.add(key)
    }
    if (failed.isEmpty()) return true
    context.add(SchemaError("additionalProperties", instancePath, "must not have additional properties"))
    return false
}

private fun validateProperties(properties: JsonObject, value: JsonObject, instancePath: String, context: ErrorContext): Boolean {
    var valid = true
    for ((key, propertySchema) in properties) {
        if (!value.containsKey(key)) continue
        if (!validateSchema(propertySchema, value[key]!!, "$instancePath/$key", context)) valid = false
    }
    return valid
}

private fun validateArrayKeywords(schema: JsonObject, value: JsonArray, instancePath: String, context: ErrorContext): Boolean {
    val items = schema["items"] ?: return true
    if (items is JsonArray && items.all(::isSchemaLike)) {
        var valid = true
        for (index in items.indices) {
            if (value.size <= index) continue
            if (!validateSchema(items[index], value[index], "$instancePath/$index", context)) valid = false
        }
        return valid
    }
    if (isSchemaLike(items)) {
        var valid = true
        for (index in value.indices) {
            if (!validateSchema(items, value[index], "$instancePath/$index", context)) valid = false
        }
        return valid
    }
    return true
}

private fun validateAllOf(schemas: JsonArray, value: JsonValue, instancePath: String, context: ErrorContext): Boolean {
    val failed = mutableListOf<ErrorContext>()
    var allValid = true
    for (schema in schemas) {
        val branch = ErrorContext()
        if (!validateSchema(schema, value, instancePath, branch)) {
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
        if (validateSchema(schema, value, instancePath, branch)) passed++ else failed.add(branch)
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
        if (validateSchema(schema, value, instancePath, branch)) passed++ else failed.add(branch)
    }
    if (passed == 1) return true
    if (passed == 0) failed.forEach { branch -> branch.errors.forEach(context::add) }
    context.add(SchemaError("oneOf", instancePath, "must match exactly one schema in oneOf"))
    return false
}

private fun isSchemaLike(value: JsonValue): Boolean = when (value) {
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
    schema is JsonObject -> validateSchema(schema, value, "", ErrorContext())
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

private fun jsonDeepCopy(value: JsonValue): JsonValue = when (value) {
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
