package dev.pi.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderErrorsTest {
    @Test
    fun normalizesBodyAndSurfacesStatusInFormattedMessage() {
        val body = """{"error":"blocked by gateway WAF"}"""
        val normalized = ProviderErrors.normalize(403, mapOf("content-type" to "application/json"), body)
        assertEquals(403, normalized.status)
        assertEquals(body, normalized.body)
        assertEquals(body, normalized.message)
        assertFalse(normalized.messageCarriesBody)
        assertEquals("403: $body", ProviderErrors.format(normalized))
        assertEquals("OpenAI API error (403): $body", ProviderErrors.format(normalized, "OpenAI API error"))
    }

    @Test
    fun synthesizesMessageForEmptyBody() {
        val normalized = ProviderErrors.normalize(429, emptyMap(), "   \n")
        assertEquals(429, normalized.status)
        assertNull(normalized.body)
        assertEquals("429 status code (no body)", normalized.message)
        assertTrue(normalized.messageCarriesBody)
        assertEquals("429 status code (no body)", ProviderErrors.format(normalized))
        assertEquals("Gateway error (429): 429 status code (no body)", ProviderErrors.format(normalized, "Gateway error"))
    }

    @Test
    fun trimsBodyWhitespace() {
        val normalized = ProviderErrors.normalize(500, emptyMap(), "\n  internal error  \n")
        assertEquals("internal error", normalized.body)
        assertEquals("500: internal error", ProviderErrors.format(normalized))
    }

    @Test
    fun truncatesLongBodies() {
        val body = "x".repeat(MAX_PROVIDER_ERROR_BODY_CHARS + 1_000)
        val normalized = ProviderErrors.normalize(502, emptyMap(), body)
        val stored = normalized.body
        assertTrue(stored != null)
        assertTrue(stored.endsWith("... [truncated 1000 chars]"))
        assertEquals(MAX_PROVIDER_ERROR_BODY_CHARS + "... [truncated 1000 chars]".length, stored.length)
    }

    @Test
    fun doesNotTruncateBodiesAtTheLimit() {
        val body = "y".repeat(MAX_PROVIDER_ERROR_BODY_CHARS)
        val normalized = ProviderErrors.normalize(502, emptyMap(), body)
        assertEquals(body, normalized.body)
    }

    @Test
    fun formatReturnsMessageWhenMessageCarriesBody() {
        val normalized = NormalizedProviderError(
            status = 500,
            body = "ignored",
            message = "boom",
            messageCarriesBody = true,
        )
        assertEquals("boom", ProviderErrors.format(normalized))
        assertEquals("Service error (500): boom", ProviderErrors.format(normalized, "Service error"))
    }

    @Test
    fun formatReturnsMessageWhenStatusIsMissing() {
        val normalized = NormalizedProviderError(
            status = null,
            body = "body",
            message = "boom",
            messageCarriesBody = false,
        )
        assertEquals("boom", ProviderErrors.format(normalized))
        assertEquals("boom", ProviderErrors.format(normalized, "Service error"))
    }

    @Test
    fun formatReturnsMessageWhenBodyIsMissing() {
        val normalized = NormalizedProviderError(
            status = 503,
            body = null,
            message = "Service Unavailable",
            messageCarriesBody = false,
        )
        assertEquals("Service Unavailable", ProviderErrors.format(normalized))
        assertEquals("Provider error (503): Service Unavailable", ProviderErrors.format(normalized, "Provider error"))
    }

    @Test
    fun truncateLeavesShortTextsUnchanged() {
        assertEquals("short", ProviderErrors.truncate("short", 10))
        assertEquals("exact", ProviderErrors.truncate("exact", 5))
    }

    @Test
    fun truncateAppendsRemovedCharacterCount() {
        assertEquals("1234... [truncated 6 chars]", ProviderErrors.truncate("1234567890", 4))
        assertEquals("... [truncated 3 chars]", ProviderErrors.truncate("abc", 0))
    }

    @Test
    fun headersDoNotAffectNormalization() {
        val withoutHeaders = ProviderErrors.normalize(500, emptyMap(), "oops")
        val withHeaders = ProviderErrors.normalize(500, mapOf("retry-after" to "5", "x-request-id" to "abc"), "oops")
        assertEquals(withoutHeaders, withHeaders)
    }
}
