package dev.pi.ai.auth.oauth

import dev.pi.ai.Models
import dev.pi.ai.auth.AuthContext
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.FileCredentialStore
import dev.pi.ai.auth.ProviderAuthResolver
import dev.pi.ai.auth.resolveAuthForProvider
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.providers.builtinProviders
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAuthChainTest {
    private fun temporaryStore(): Pair<CredentialStore, Path> {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-auth-chain-${System.nanoTime()}"
        return FileCredentialStore(directory) to directory
    }

    private fun expired(providerId: String, store: CredentialStore, refresh: String, access: String) {
        saveOAuthCredential(
            providerId,
            store,
            OAuthCredential(refresh = refresh, access = access, expires = 1),
        )
    }

    private fun fresh(providerId: String, store: CredentialStore, refresh: String, access: String) {
        saveOAuthCredential(
            providerId,
            store,
            OAuthCredential(refresh = refresh, access = access, expires = platformCurrentTimeMillis() + 3_600_000),
        )
    }

    @Test
    fun expiresKimiOAuthRefreshesAndAppliesBearerHeader() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("kimi-coding", store, "kimi-refresh-old", "kimi-access-old")
            val transport = FakeHttpTransport { request ->
                assertEquals("https://auth.kimi.com/api/oauth/token", request.url)
                jsonHttpResponse(
                    """{"access_token":"kimi-access-new","refresh_token":"kimi-refresh-new","expires_in":3600}""",
                )
            }
            val resolver = ProviderAuthResolver(
                providerId = "kimi-coding",
                store = store,
                oauthFlow = bundledOAuthFlows()["kimi-coding"],
                transport = transport,
            )
            val resolved = resolver.resolve()!!

            assertNull(resolved.apiKey)
            assertEquals("Bearer kimi-access-new", resolved.headers["Authorization"])
            assertEquals("OAuth", resolved.source)
            assertEquals(1, transport.requests.size)
            assertEquals("refresh_token", transport.requests.single().formBody()["grant_type"])
            assertEquals("kimi-refresh-old", transport.requests.single().formBody()["refresh_token"])

            val persisted = loadOAuthCredential("kimi-coding", store)!!
            assertEquals("kimi-access-new", persisted.access)
            assertEquals("kimi-refresh-new", persisted.refresh)
            assertTrue(persisted.expires > platformCurrentTimeMillis())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun expiredAnthropicOAuthRefreshesAndReturnsApiKey() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("anthropic", store, "anthropic-refresh-old", "anthropic-access-old")
            val transport = FakeHttpTransport {
                jsonHttpResponse(
                    """{"access_token":"anthropic-access-new","refresh_token":"anthropic-refresh-new","expires_in":3600}""",
                )
            }
            val resolved = resolveAuthForProvider(
                providerId = "anthropic",
                store = store,
                oauthFlow = bundledOAuthFlows()["anthropic"],
                transport = transport,
            )!!

            assertEquals("anthropic-access-new", resolved.apiKey)
            assertEquals("OAuth", resolved.source)
            assertEquals(1, transport.requests.size)
            assertEquals("anthropic-access-new", loadOAuthCredential("anthropic", store)?.access)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun freshCredentialsSkipRefreshAndUseToAuth() = runTest {
        val (store, directory) = temporaryStore()
        try {
            fresh("kimi-coding", store, "kimi-refresh", "kimi-access")
            fresh("anthropic", store, "anthropic-refresh", "anthropic-access")
            val transport = FakeHttpTransport { error("no network expected") }

            val kimi = ProviderAuthResolver(
                "kimi-coding",
                store,
                oauthFlow = bundledOAuthFlows()["kimi-coding"],
                transport = transport,
            ).resolve()!!
            assertEquals("Bearer kimi-access", kimi.headers["Authorization"])
            assertEquals("OAuth", kimi.source)

            val anthropic = ProviderAuthResolver(
                "anthropic",
                store,
                oauthFlow = bundledOAuthFlows()["anthropic"],
                transport = transport,
            ).resolve()!!
            assertEquals("anthropic-access", anthropic.apiKey)
            assertTrue(transport.requests.isEmpty())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun explicitOverrideWinsWithoutRefresh() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("kimi-coding", store, "kimi-refresh-old", "kimi-access-old")
            val transport = FakeHttpTransport { error("no network expected") }
            val resolver = ProviderAuthResolver(
                "kimi-coding",
                store,
                oauthFlow = bundledOAuthFlows()["kimi-coding"],
                transport = transport,
            )
            val resolved = resolver.resolve(AuthOverrides(apiKey = "explicit-override"))!!
            assertEquals("explicit-override", resolved.apiKey)
            assertEquals("runtime", resolved.source)
            assertTrue(transport.requests.isEmpty())
            assertEquals("kimi-access-old", loadOAuthCredential("kimi-coding", store)?.access)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun storedApiKeyWinsOverEnvironment() = runTest {
        val (store, directory) = temporaryStore()
        try {
            store.writeApiKey("deepseek", "sk-stored")
            withAuthEnv(mapOf("DEEPSEEK_API_KEY" to "sk-env")) {
                val resolved = ProviderAuthResolver(
                    "deepseek",
                    store,
                    envVars = listOf("DEEPSEEK_API_KEY"),
                ).resolve()!!
                assertEquals("sk-stored", resolved.apiKey)
                assertEquals("stored credential", resolved.source)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun fallsBackToEnvironmentWithoutStoredCredential() = runTest {
        val (store, directory) = temporaryStore()
        try {
            withAuthEnv(mapOf("DEEPSEEK_API_KEY" to "sk-env")) {
                val resolved = ProviderAuthResolver(
                    "deepseek",
                    store,
                    envVars = listOf("DEEPSEEK_API_KEY"),
                ).resolve()!!
                assertEquals("sk-env", resolved.apiKey)
                assertEquals("DEEPSEEK_API_KEY", resolved.source)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun storedOAuthWithoutFlowDoesNotFallBackToEnvironment() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("custom-provider", store, "refresh", "access")
            withAuthEnv(mapOf("CUSTOM_API_KEY" to "sk-env")) {
                val resolved = ProviderAuthResolver(
                    "custom-provider",
                    store,
                    envVars = listOf("CUSTOM_API_KEY"),
                ).resolve()
                assertNull(resolved)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun refreshFailuresAreWrappedAndKeepStoredCredential() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("kimi-coding", store, "kimi-refresh-old", "kimi-access-old")
            val transport = FakeHttpTransport { FakeHttpResponse(status = 500, body = "boom") }
            val resolver = ProviderAuthResolver(
                "kimi-coding",
                store,
                oauthFlow = bundledOAuthFlows()["kimi-coding"],
                transport = transport,
            )
            val error = assertFailsWith<IllegalStateException> { resolver.resolve() }
            assertEquals("OAuth refresh failed for kimi-coding", error.message)
            assertTrue(error.cause != null)
            assertEquals("kimi-access-old", loadOAuthCredential("kimi-coding", store)?.access)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun modelsGetAuthTriggersOAuthRefreshThroughBuiltinProviders() = runTest {
        val (store, directory) = temporaryStore()
        try {
            expired("kimi-coding", store, "kimi-refresh-old", "kimi-access-old")
            val transport = FakeHttpTransport {
                jsonHttpResponse(
                    """{"access_token":"kimi-access-new","refresh_token":"kimi-refresh-new","expires_in":3600}""",
                )
            }
            val models = Models()
            models.setProvider(builtinProviders(transport, store).first { it.id == "kimi-coding" })

            val resolved = models.getAuth("kimi-coding")!!
            assertEquals("Bearer kimi-access-new", resolved.headers["Authorization"])
            assertEquals("OAuth", resolved.source)
            assertEquals(1, transport.requests.size)
            assertEquals("kimi-access-new", loadOAuthCredential("kimi-coding", store)?.access)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private suspend fun withAuthEnv(env: Map<String, String>, block: suspend () -> Unit) {
        val previous = AuthEnvironment.context
        AuthEnvironment.context = AuthContext { name -> env[name] }
        try {
            block()
        } finally {
            AuthEnvironment.context = previous
        }
    }
}
