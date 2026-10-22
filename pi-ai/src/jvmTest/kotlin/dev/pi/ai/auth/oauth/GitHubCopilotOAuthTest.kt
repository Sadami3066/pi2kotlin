package dev.pi.ai.auth.oauth

import dev.pi.ai.ModelCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCopilotOAuthTest {
    private val knownModelId: String = ModelCatalog.findByProvider("github-copilot").first().id

    private fun deviceCodeResponse(): String =
        """{"device_code":"device-code","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":1,"expires_in":60}"""

    private fun copilotTokenResponse(token: String): String =
        """{"token":"$token","expires_at":1000000}"""

    private fun modelsResponse(): String {
        val items = listOf(
            """{"id":"$knownModelId","model_picker_enabled":true,"policy":{"state":"unconfigured"},"capabilities":{"supports":{"tool_calls":true}}}""",
            """{"id":"picker-model","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":true}}}""",
            """{"id":"non-tool-model","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":false}}}""",
        )
        return """{"data":[${items.joinToString(",")}]}"""
    }

    @Test
    fun deviceFlowLoginRefreshesCopilotTokenAndEnablesModels() = runTest {
        var accessTokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/login/device/code") -> jsonHttpResponse(deviceCodeResponse())
                request.url.endsWith("/login/oauth/access_token") -> {
                    accessTokenPolls += 1
                    if (accessTokenPolls == 1) {
                        jsonHttpResponse("""{"error":"authorization_pending"}""")
                    } else {
                        jsonHttpResponse("""{"access_token":"github-access-token"}""")
                    }
                }

                request.url.endsWith("/copilot_internal/v2/token") -> jsonHttpResponse(
                    copilotTokenResponse("copilot-token;proxy-ep=proxy.individual.githubcopilot.com;exp=999"),
                )

                request.url.endsWith("/models") -> jsonHttpResponse(modelsResponse())
                request.url.endsWith("/models/$knownModelId/policy") -> jsonHttpResponse("""{}""")
                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(""))
        val credential = withOAuthRuntime {
            githubCopilotOAuth.login(interaction, transport)
        }

        assertEquals("copilot-token;proxy-ep=proxy.individual.githubcopilot.com;exp=999", credential.access)
        assertEquals("github-access-token", credential.refresh)
        assertEquals(1_000_000_000L - 300_000L, credential.expires)
        assertNull(credential.enterpriseUrl)
        val availableModelIds = credential.availableModelIds!!
        assertTrue(availableModelIds.contains(knownModelId))
        assertTrue(availableModelIds.contains("picker-model"))
        assertTrue(!availableModelIds.contains("non-tool-model"))
        assertEquals(2, accessTokenPolls)

        val deviceRequest = transport.requests.first { it.url.endsWith("/login/device/code") }
        val deviceForm = deviceRequest.formBody()
        assertEquals("Iv1.b507a08c87ecfe98", deviceForm["client_id"])
        assertEquals("read:user", deviceForm["scope"])
        assertEquals("GitHubCopilotChat/0.35.0", deviceRequest.header("user-agent"))

        val accessRequest = transport.requests.filter { it.url.endsWith("/login/oauth/access_token") }.last()
        val accessForm = accessRequest.formBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", accessForm["grant_type"])
        assertEquals("device-code", accessForm["device_code"])

        val copilotTokenRequest = transport.requests.first { it.url.endsWith("/copilot_internal/v2/token") }
        assertEquals("Bearer github-access-token", copilotTokenRequest.header("authorization"))
        assertEquals("GitHubCopilotChat/0.35.0", copilotTokenRequest.header("user-agent"))
        assertEquals("vscode/1.107.0", copilotTokenRequest.header("editor-version"))
        assertEquals("copilot-chat/0.35.0", copilotTokenRequest.header("editor-plugin-version"))
        assertEquals("vscode-chat", copilotTokenRequest.header("copilot-integration-id"))

        val modelsRequest = transport.requests.first { it.url.endsWith("/models") }
        assertEquals(
            "Bearer copilot-token;proxy-ep=proxy.individual.githubcopilot.com;exp=999",
            modelsRequest.header("authorization"),
        )
        assertEquals("2026-06-01", modelsRequest.header("x-github-api-version"))

        val policyRequest = transport.requests.first { it.url.endsWith("/models/$knownModelId/policy") }
        assertEquals("POST", policyRequest.method)
        assertEquals("""{"state":"enabled"}""", policyRequest.bodyText())
        assertEquals("chat-policy", policyRequest.header("openai-intent"))

        assertEquals("copilot-token;proxy-ep=proxy.individual.githubcopilot.com;exp=999", githubCopilotOAuth.toAuth(credential).apiKey)
        assertEquals(
            "https://api.individual.githubcopilot.com",
            githubCopilotBaseUrl(credential.access, null),
        )
        assertTrue(interaction.openedUrls.single() == "https://github.com/login/device")
        assertTrue(interaction.messages.any { it.contains("Enabling models") })
    }

    @Test
    fun loginUsesEnterpriseDomainForEndpoints() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url == "https://company.ghe.com/login/device/code" -> jsonHttpResponse(deviceCodeResponse())
                request.url == "https://company.ghe.com/login/oauth/access_token" -> jsonHttpResponse(
                    """{"access_token":"github-access-token"}""",
                )

                request.url == "https://api.company.ghe.com/copilot_internal/v2/token" -> jsonHttpResponse(
                    copilotTokenResponse("copilot-token"),
                )

                request.url == "https://copilot-api.company.ghe.com/models" -> jsonHttpResponse("""{"data":[]}""")
                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("company.ghe.com"))
        val credential = withOAuthRuntime {
            githubCopilotOAuth.login(interaction, transport)
        }
        assertEquals("company.ghe.com", credential.enterpriseUrl)
        assertEquals("https://copilot-api.company.ghe.com", githubCopilotBaseUrl("copilot-token", "company.ghe.com"))
        assertTrue(transport.requests.any { it.url.startsWith("https://company.ghe.com/") })
    }

    @Test
    fun loginRejectsInvalidEnterpriseDomain() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("{}") }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("not a domain"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime {
                githubCopilotOAuth.login(interaction, transport)
            }
        }
        assertEquals("Invalid GitHub Enterprise URL/domain", error.message)
    }

    @Test
    fun reportsDeviceTokenFailures() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/login/device/code") -> jsonHttpResponse(deviceCodeResponse())
                request.url.endsWith("/login/oauth/access_token") -> jsonHttpResponse(
                    """{"error":"access_denied","error_description":"user said no"}""",
                )

                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(""))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime {
                githubCopilotOAuth.login(interaction, transport)
            }
        }
        assertEquals("Device flow failed: access_denied: user said no", error.message)
    }

    @Test
    fun refreshFetchesModelsWithAvailableIds() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/copilot_internal/v2/token") -> jsonHttpResponse(
                    copilotTokenResponse("copilot-token;proxy-ep=proxy.business.githubcopilot.com"),
                )

                request.url.endsWith("/models") -> jsonHttpResponse(
                    """{"data":[{"id":"picker-model","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":true}}}]}""",
                )

                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val credential = githubCopilotOAuth.refresh(
            OAuthCredential(refresh = "github-access-token", access = "old", expires = 1),
            transport,
        )
        assertEquals("copilot-token;proxy-ep=proxy.business.githubcopilot.com", credential.access)
        assertEquals("github-access-token", credential.refresh)
        assertEquals(listOf("picker-model"), credential.availableModelIds)
        assertEquals(
            "https://api.business.githubcopilot.com",
            githubCopilotBaseUrl(credential.access),
        )
    }

    @Test
    fun parsesProxyEndpointFromToken() {
        assertEquals(
            "https://api.individual.githubcopilot.com",
            githubCopilotBaseUrl("tid=1;proxy-ep=proxy.individual.githubcopilot.com;exp=1"),
        )
        assertEquals("https://api.individual.githubcopilot.com", githubCopilotBaseUrl("no-proxy-endpoint"))
        assertEquals("https://copilot-api.company.ghe.com", githubCopilotBaseUrl("no-proxy-endpoint", "company.ghe.com"))
    }

    @Test
    fun normalizesEnterpriseDomains() {
        assertEquals("company.ghe.com", normalizeGitHubEnterpriseDomain("https://company.ghe.com/"))
        assertEquals("company.ghe.com", normalizeGitHubEnterpriseDomain("company.ghe.com"))
        assertNull(normalizeGitHubEnterpriseDomain(""))
        assertNull(normalizeGitHubEnterpriseDomain("not a domain"))
    }
}
