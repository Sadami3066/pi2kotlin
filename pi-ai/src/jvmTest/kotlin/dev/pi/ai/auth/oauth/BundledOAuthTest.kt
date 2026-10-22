package dev.pi.ai.auth.oauth

import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledOAuthTest {
    private fun temporaryStore(): Pair<CredentialStore, Path> {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-oauth-${System.nanoTime()}"
        return CredentialStore(directory) to directory
    }

    @Test
    fun bundlesTheImplementedProviderFlows() {
        val flows = bundledOAuthFlows()
        assertEquals(setOf("anthropic", "openai", "github-copilot"), flows.keys)
        assertEquals("Anthropic (Claude Pro/Max)", flows["anthropic"]!!.name)
        assertEquals("OpenAI (ChatGPT subscription)", flows["openai"]!!.name)
        assertEquals("GitHub Copilot", flows["github-copilot"]!!.name)
        assertEquals("oauth", flows["anthropic"]!!.type)
        assertTrue(flows["openai"]!!.isSubscription)
        assertEquals("Sign in with ChatGPT", flows["openai"]!!.loginLabel)
    }

    @Test
    fun resolvesFreshStoredCredentialWithoutNetwork() = runTest {
        val (store, directory) = temporaryStore()
        try {
            saveOAuthCredential(
                "anthropic",
                store,
                OAuthCredential(refresh = "refresh-1", access = "access-1", expires = oauthNowMillis() + 3_600_000),
            )
            val transport = FakeHttpTransport { error("no network expected") }
            val resolver = oauthResolver("anthropic", anthropicOAuth, store, transport)
            val auth = resolver.resolve()
            assertEquals("access-1", auth?.apiKey)
            assertEquals("OAuth", auth?.source)
            assertTrue(transport.requests.isEmpty())

            val raw = store.readRaw()["anthropic"]!!.jsonObject
            assertEquals("oauth", raw["type"]?.jsonPrimitive?.content)
            assertEquals("refresh-1", raw["refresh"]?.jsonPrimitive?.content)
            assertEquals("access-1", raw["access"]?.jsonPrimitive?.content)
            assertTrue(raw.containsKey("expires"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun refreshesExpiredCredentialAndPersistsResult() = runTest {
        val (store, directory) = temporaryStore()
        try {
            saveOAuthCredential(
                "anthropic",
                store,
                OAuthCredential(refresh = "refresh-old", access = "access-old", expires = 0),
            )
            val transport = FakeHttpTransport {
                jsonHttpResponse("""{"access_token":"access-new","refresh_token":"refresh-new","expires_in":3600}""")
            }
            val resolver = oauthResolver("anthropic", anthropicOAuth, store, transport)
            val auth = resolver.resolve()
            assertEquals("access-new", auth?.apiKey)
            assertEquals(1, transport.requests.size)

            val persisted = loadOAuthCredential("anthropic", store)
            assertEquals("access-new", persisted?.access)
            assertEquals("refresh-new", persisted?.refresh)
            assertTrue(persisted!!.expires > oauthNowMillis())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun returnsNullWhenNoOAuthCredentialIsStored() = runTest {
        val (store, directory) = temporaryStore()
        try {
            val transport = FakeHttpTransport { error("no network expected") }
            val resolver = oauthResolver("anthropic", anthropicOAuth, store, transport)
            assertNull(resolver.resolve())
            assertTrue(transport.requests.isEmpty())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun explicitApiKeyOverrideWins() = runTest {
        val (store, directory) = temporaryStore()
        try {
            val transport = FakeHttpTransport { error("no network expected") }
            val resolver = oauthResolver("anthropic", anthropicOAuth, store, transport)
            val auth = resolver.resolve(AuthOverrides(apiKey = "explicit-key"))
            assertEquals("explicit-key", auth?.apiKey)
            assertEquals("stored credential", auth?.source)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun refreshFailuresAreWrapped() = runTest {
        val (store, directory) = temporaryStore()
        try {
            saveOAuthCredential(
                "anthropic",
                store,
                OAuthCredential(refresh = "refresh-old", access = "access-old", expires = 0),
            )
            val transport = FakeHttpTransport { FakeHttpResponse(status = 500, body = "boom") }
            val resolver = oauthResolver("anthropic", anthropicOAuth, store, transport)
            val error = assertFailsWith<IllegalStateException> { resolver.resolve() }
            assertEquals("OAuth refresh failed for anthropic", error.message)
            assertTrue(error.cause != null)
            assertEquals("access-old", loadOAuthCredential("anthropic", store)?.access)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun persistsExtendedCredentialFields() = runTest {
        val (store, directory) = temporaryStore()
        try {
            val credential = OAuthCredential(
                refresh = "refresh",
                access = "access",
                expires = 1234,
                clientId = "client-id",
                scopes = listOf("openid", "profile"),
                enterpriseUrl = "company.ghe.com",
                availableModelIds = listOf("model-a", "model-b"),
                extra = mapOf("custom" to kotlinx.serialization.json.JsonPrimitive("kept")),
            )
            saveOAuthCredential("openai", store, credential)
            val loaded = loadOAuthCredential("openai", store)
            assertEquals(credential, loaded)
            val raw = store.readRaw()["openai"]!!.jsonObject
            assertEquals("client-id", raw["clientId"]?.jsonPrimitive?.content)
            assertEquals("kept", raw["custom"]?.jsonPrimitive?.content)
            assertTrue(store.read("openai") is dev.pi.ai.auth.OAuthCredential)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun loginAndStorePersistsNewCredential() = runTest {
        val (store, directory) = temporaryStore()
        try {
            val verifier = deterministicVerifier()
            val transport = FakeHttpTransport {
                jsonHttpResponse("""{"access_token":"access-login","refresh_token":"refresh-login","expires_in":3600}""")
            }
            val interaction = FakeOAuthInteraction(
                selectAnswers = mutableListOf("copy_code"),
                promptAnswers = mutableListOf("the-code#$verifier"),
            )
            val credential = withOAuthRuntime(randomBytes = deterministicRandom()) {
                loginAndStoreOAuthCredential("anthropic", anthropicOAuth, store, transport, interaction)
            }
            assertEquals("access-login", credential.access)
            assertEquals("access-login", loadOAuthCredential("anthropic", store)?.access)
            assertEquals("access-login", store.readRaw()["anthropic"]!!.jsonObject["access"]?.jsonPrimitive?.content)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }
}
