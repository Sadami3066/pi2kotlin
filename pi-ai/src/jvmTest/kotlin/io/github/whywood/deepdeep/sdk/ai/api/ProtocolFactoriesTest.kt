package io.github.whywood.deepdeep.sdk.ai.api

import io.github.whywood.deepdeep.sdk.ai.http.HttpRequest
import io.github.whywood.deepdeep.sdk.ai.http.HttpResponse
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtocolFactoriesTest {
    private class NoopTransport : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("network is not available in tests")
    }

    @Test
    fun exposesFactoryForEveryKnownChatApi() {
        val transport = NoopTransport()
        assertEquals(10, CHAT_APIS.size)
        for (api in CHAT_APIS) {
            val factory = assertNotNull(chatApiFactory(api, transport), "missing factory for $api")
            assertEquals(api, factory().api)
        }
    }

    @Test
    fun returnsNullForUnknownChatApi() {
        assertNull(chatApiFactory("unknown-chat-api", NoopTransport()))
        assertNull(chatApiFactory("", NoopTransport()))
        assertNull(chatApiFactory("openrouter-images", NoopTransport()))
    }

    @Test
    fun chatApiListIsUnique() {
        assertEquals(CHAT_APIS.size, CHAT_APIS.distinct().size)
    }
}
