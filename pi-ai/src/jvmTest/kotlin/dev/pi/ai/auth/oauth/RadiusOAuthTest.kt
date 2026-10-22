package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RadiusOAuthTest {
    private fun deviceAuthorizationResponse(): String =
        """{"device_code":"radius-device","user_code":"RADIUS-CODE","verification_uri":"https://radius.pi.dev/device","expires_in":60,"interval":1}"""

    @Test
    fun deviceCodeLoginPollsUntilToken() = runTest {
        var tokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url == "https://radius.pi.dev/v1/oauth/device" -> jsonHttpResponse(deviceAuthorizationResponse())
                request.url == "https://radius.pi.dev/v1/oauth/token" -> {
                    tokenPolls += 1
                    if (tokenPolls == 1) {
                        jsonHttpResponse("""{"error":"authorization_pending"}""", status = 400)
                    } else {
                        jsonHttpResponse(
                            """{"access_token":"radius-access","refresh_token":"radius-refresh","expires_in":3600,"scope":"gateway offline_access"}""",
                        )
                    }
                }

                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("device-code"))
        val credential = radiusOAuth.login(interaction, transport)

        assertEquals("radius-access", credential.access)
        assertEquals("radius-refresh", credential.refresh)
        assertEquals("gateway offline_access", credential.extra["scope"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(2, tokenPolls)

        val deviceForm = transport.requests.first().formBody()
        assertEquals("pi-gateway", deviceForm["client_id"])
        assertEquals("gateway offline_access", deviceForm["scope"])

        val tokenForm = transport.requests.last().formBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", tokenForm["grant_type"])
        assertEquals("radius-device", tokenForm["device_code"])
        assertEquals("https://radius.pi.dev/device", interaction.openedUrls.single())
        assertTrue(interaction.openedInstructions.single()!!.contains("RADIUS-CODE"))
        assertEquals("Sign in to Radius:", interaction.selectMessages.single())
    }

    @Test
    fun deviceCodeReportsDeniedAndExpired() = runTest {
        fun transportFor(error: String): FakeHttpTransport = FakeHttpTransport { request ->
            when (request.url) {
                "https://radius.pi.dev/v1/oauth/device" -> jsonHttpResponse(deviceAuthorizationResponse())
                else -> jsonHttpResponse("""{"error":"$error"}""", status = 400)
            }
        }
        val expired = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("device-code")), transportFor("expired_token"))
        }
        assertEquals("Device authorization expired.", expired.message)
        val denied = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("device-code")), transportFor("access_denied"))
        }
        assertEquals("Device authorization was denied.", denied.message)
    }

    @Test
    fun reportsDeviceAuthorizationFailures() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error":"invalid_client","error_description":"bad gateway"}""", status = 401)
        }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("device-code")), transport)
        }
        assertEquals("Radius OAuth device authorization failed: invalid_client: bad gateway", error.message)
    }

    @Test
    fun rejectsInvalidDeviceAuthorizationResponse() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"device_code":"only"}""") }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("device-code")), transport)
        }
        assertEquals("Radius OAuth device authorization response is missing required fields", error.message)
    }

    @Test
    fun browserLoginLoadsDiscoveryAndReportsFailures() = runTest {
        val transport = FakeHttpTransport { FakeHttpResponse(status = 500, body = "gateway down") }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("browser")), transport)
        }
        assertEquals(
            "Could not load Radius OAuth config from https://radius.pi.dev: 500 gateway down",
            error.message,
        )
    }

    @Test
    fun rejectsInvalidDiscoveryResponse() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"authorizationEndpoint":42}""") }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("browser")), transport)
        }
        assertEquals("Invalid Radius OAuth config from https://radius.pi.dev", error.message)
    }

    @Test
    fun rejectsUnknownLoginMethod() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("other")), FakeHttpTransport { jsonHttpResponse("{}") })
        }
        assertEquals("Unknown Radius sign-in method: other", error.message)
    }

    @Test
    fun refreshExchangesRefreshToken() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"radius-access-2","refresh_token":"radius-refresh-2","expires_in":1800}""")
        }
        val credential = radiusOAuth.refresh(
            OAuthCredential(refresh = "radius-refresh", access = "radius-access", expires = 1),
            transport,
        )
        assertEquals("radius-access-2", credential.access)
        assertEquals("radius-refresh-2", credential.refresh)
        val form = transport.requests.single().formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("radius-refresh", form["refresh_token"])
    }

    @Test
    fun reportsTokenRequestFailures() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error":"invalid_grant","error_description":"nope"}""", status = 400)
        }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.refresh(OAuthCredential(refresh = "r", access = "a", expires = 1), transport)
        }
        assertEquals("Radius OAuth token request failed: invalid_grant: nope", error.message)
    }

    @Test
    fun mapsAbortToLoginCancelled() = runTest {
        val signal = RunSignal().apply { abort() }
        val transport = FakeHttpTransport { throw IllegalStateException("transport failed after abort") }
        val error = assertFailsWith<IllegalStateException> {
            radiusOAuth.login(FakeOAuthInteraction(selectAnswers = mutableListOf("device-code"), signal = signal), transport)
        }
        assertEquals(OAUTH_CANCEL_MESSAGE, error.message)
    }

    @Test
    fun supportsCustomGatewayAndName() = runTest {
        val flow = radiusOAuthFlow(gateway = "gw.example.test", name = "Acme Gateway", id = "acme")
        assertEquals("acme", flow.id)
        assertEquals("Acme Gateway", flow.name)
        assertEquals("https://gw.example.test", normalizeRadiusGatewayUrl("gw.example.test"))
        assertEquals("https://gw.example.test", normalizeRadiusGatewayUrl("https://gw.example.test///"))

        val transport = FakeHttpTransport { request ->
            when (request.url) {
                "https://gw.example.test/v1/oauth/device" -> jsonHttpResponse(deviceAuthorizationResponse())
                else -> jsonHttpResponse(
                    """{"access_token":"acme-access","refresh_token":"acme-refresh","expires_in":1800}""",
                )
            }
        }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("device-code"))
        flow.login(interaction, transport)
        assertEquals("Sign in to Acme Gateway:", interaction.selectMessages.single())
        assertTrue(transport.requests.first().url == "https://gw.example.test/v1/oauth/device")
    }

    @Test
    fun toAuthUsesAccessToken() = runTest {
        val auth = radiusOAuth.toAuth(OAuthCredential(refresh = "r", access = "radius-access", expires = 1))
        assertEquals("radius-access", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }
}
