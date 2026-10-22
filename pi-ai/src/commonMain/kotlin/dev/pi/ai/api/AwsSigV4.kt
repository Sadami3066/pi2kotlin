package dev.pi.ai.api

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.auth.oauth.sha256

data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

data class AwsSignedRequest(
    val url: String,
    val headers: Map<String, String>,
)

data class AwsDateTime(
    val date: String,
    val timestamp: String,
)

object AwsSigV4 {
    const val ALGORITHM: String = "AWS4-HMAC-SHA256"
    const val BEDROCK_SERVICE: String = "bedrock"

    private val unsignableHeaders = setOf(
        "authorization",
        "user-agent",
        "x-amzn-trace-id",
        "expect",
        "transfer-encoding",
        "connection",
        "content-length",
    )

    fun sign(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: ByteArray,
        credentials: AwsCredentials,
        region: String,
        service: String,
        epochMillis: Long = platformCurrentTimeMillis(),
    ): AwsSignedRequest {
        val parsed = parseUrl(url)
        val dateTime = formatAmzDate(epochMillis)
        val signable = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            if (name.lowercase() !in unsignableHeaders) signable[name] = value
        }
        signable.keys.filter { it.equals("host", ignoreCase = true) }.forEach { signable.remove(it) }
        signable["host"] = parsed.hostHeader
        signable["x-amz-date"] = dateTime.timestamp
        credentials.sessionToken?.takeIf { it.isNotEmpty() }?.let { signable["x-amz-security-token"] = it }

        val canonicalHeaders = signable.entries
            .map { (name, value) -> name.lowercase() to normalizeHeaderValue(value) }
            .sortedBy { it.first }
        val signedHeaders = canonicalHeaders.joinToString(";") { it.first }
        val canonicalRequest = buildString {
            append(method.uppercase())
            append('\n')
            append(parsed.path.ifEmpty { "/" })
            append('\n')
            append(canonicalQuery(parsed.query))
            append('\n')
            for ((name, value) in canonicalHeaders) {
                append(name)
                append(':')
                append(value)
                append('\n')
            }
            append('\n')
            append(signedHeaders)
            append('\n')
            append(sha256Hex(payload))
        }
        val scope = "${dateTime.date}/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM)
            append('\n')
            append(dateTime.timestamp)
            append('\n')
            append(scope)
            append('\n')
            append(sha256Hex(canonicalRequest.encodeToByteArray()))
        }
        val signature = AwsSigV4.toHex(
            hmacSha256(
                deriveSigningKey(credentials.secretAccessKey, dateTime.date, region, service),
                stringToSign.encodeToByteArray(),
            ),
        )

        val resultHeaders = LinkedHashMap(headers)
        resultHeaders["x-amz-date"] = dateTime.timestamp
        credentials.sessionToken?.takeIf { it.isNotEmpty() }?.let { resultHeaders["x-amz-security-token"] = it }
        resultHeaders["Authorization"] =
            "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        return AwsSignedRequest(url, resultHeaders)
    }

    fun formatAmzDate(epochMillis: Long): AwsDateTime {
        val seconds = epochMillis.floorDiv(1000L)
        val days = seconds.floorDiv(86_400L)
        val secondsOfDay = ((seconds % 86_400L) + 86_400L) % 86_400L
        val (year, month, day) = civilFromDays(days)
        val date = year.toString().padStart(4, '0') +
            month.toString().padStart(2, '0') +
            day.toString().padStart(2, '0')
        val hour = (secondsOfDay / 3600L).toString().padStart(2, '0')
        val minute = ((secondsOfDay % 3600L) / 60L).toString().padStart(2, '0')
        val second = (secondsOfDay % 60L).toString().padStart(2, '0')
        return AwsDateTime(date = date, timestamp = date + "T" + hour + minute + second + "Z")
    }

    fun deriveSigningKey(secretAccessKey: String, date: String, region: String, service: String): ByteArray {
        val dateKey = hmacSha256(("AWS4" + secretAccessKey).encodeToByteArray(), date.encodeToByteArray())
        val regionKey = hmacSha256(dateKey, region.encodeToByteArray())
        val serviceKey = hmacSha256(regionKey, service.encodeToByteArray())
        return hmacSha256(serviceKey, "aws4_request".encodeToByteArray())
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val normalizedKey = if (key.size > blockSize) sha256(key) else key
        val paddedKey = ByteArray(blockSize)
        normalizedKey.copyInto(paddedKey)
        val inner = ByteArray(blockSize + data.size)
        val outer = ByteArray(blockSize + 32)
        for (index in 0 until blockSize) {
            inner[index] = (paddedKey[index].toInt() xor 0x36).toByte()
            outer[index] = (paddedKey[index].toInt() xor 0x5c).toByte()
        }
        data.copyInto(inner, blockSize)
        sha256(inner).copyInto(outer, blockSize)
        return sha256(outer)
    }

    fun sha256Hex(data: ByteArray): String = toHex(sha256(data))

    fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(digits[value ushr 4])
            builder.append(digits[value and 0x0F])
        }
        return builder.toString()
    }

    fun awsPercentEncode(value: String): String {
        val hexDigits = "0123456789ABCDEF"
        val builder = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (unsigned <= 0x7F && (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == '~')) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(hexDigits[unsigned ushr 4])
                builder.append(hexDigits[unsigned and 0x0F])
            }
        }
        return builder.toString()
    }

    private fun normalizeHeaderValue(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun canonicalQuery(query: String): String {
        if (query.isEmpty()) return ""
        return query.split('&')
            .filter { it.isNotEmpty() }
            .map { part ->
                val separator = part.indexOf('=')
                val name = if (separator < 0) part else part.substring(0, separator)
                val value = if (separator < 0) "" else part.substring(separator + 1)
                awsPercentEncode(name) + "=" + awsPercentEncode(value)
            }
            .sorted()
            .joinToString("&")
    }

    private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
        val z = days + 719468L
        val era = if (z >= 0L) z / 146097L else (z - 146096L) / 146097L
        val dayOfEra = z - era * 146097L
        val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
        val yearBase = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPart = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPart + 2L) / 5L + 1L
        val month = monthPart + if (monthPart < 10L) 3L else -9L
        val year = yearBase + if (month <= 2L) 1L else 0L
        return Triple(year, month, day)
    }

    private data class ParsedUrl(val hostHeader: String, val path: String, val query: String)

    private fun parseUrl(url: String): ParsedUrl {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
        val slashIndex = rest.indexOf('/')
        val authority = if (slashIndex < 0) rest else rest.substring(0, slashIndex)
        var pathAndQuery = if (slashIndex < 0) "" else rest.substring(slashIndex)
        val fragmentIndex = pathAndQuery.indexOf('#')
        if (fragmentIndex >= 0) pathAndQuery = pathAndQuery.substring(0, fragmentIndex)
        val queryIndex = pathAndQuery.indexOf('?')
        val query = if (queryIndex < 0) "" else pathAndQuery.substring(queryIndex + 1)
        val path = if (queryIndex < 0) pathAndQuery else pathAndQuery.substring(0, queryIndex)
        val hostHeader = if (authority.contains('@')) authority.substringAfter('@') else authority
        return ParsedUrl(hostHeader = hostHeader, path = path, query = query)
    }
}
