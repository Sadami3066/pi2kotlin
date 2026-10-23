package dev.pi.mcp

import dev.pi.mcp.config.McpConfigException
import dev.pi.mcp.config.McpConfigParseResult
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.config.applyMcpServerOverrides
import dev.pi.mcp.config.parseMcpConfig
import dev.pi.mcp.config.parseMcpServers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpConfigParserTest {
    private fun parse(json: String): McpConfigParseResult =
        parseMcpConfig(Json.parseToJsonElement(json).jsonObject)

    private fun assertHasError(result: McpConfigParseResult, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue(
                result.errors.any { it.contains(fragment) },
                "expected error containing \"$fragment\" in ${result.errors}",
            )
        }
    }

    @Test
    fun parsesStdioServerWithDefaults() {
        val result = parse(
            """{"mcpServers":{"fs":{"command":"npx","args":["-y","server"]}}}""",
        )
        assertTrue(result.errors.isEmpty())
        val config = result.servers.single().second as McpServerConfig.Stdio
        assertEquals("fs", result.servers.single().first)
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "server"), config.args)
        assertEquals(emptyMap(), config.env)
        assertNull(config.cwd)
        assertEquals(true, config.enabled)
        assertEquals(60L, config.timeoutSeconds)
        assertNull(config.description)
        assertEquals(McpExposure.Deferred, config.exposure)
        assertEquals(emptyMap(), config.toolExposure)
    }

    @Test
    fun parsesFullStdioServer() {
        val result = parse(
            """
            {"mcpServers":{"fs":{
              "type":"stdio",
              "command":"node",
              "args":["server.js"],
              "env":{"TOKEN":"secret"},
              "cwd":"/tmp/work",
              "enabled":false,
              "timeout":120,
              "description":"filesystem tools",
              "exposure":"direct",
              "toolExposure":{"read_*":"hidden","write":"direct"}
            }}}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val config = result.servers.single().second as McpServerConfig.Stdio
        assertEquals("node", config.command)
        assertEquals(listOf("server.js"), config.args)
        assertEquals(mapOf("TOKEN" to "secret"), config.env)
        assertEquals("/tmp/work", config.cwd)
        assertEquals(false, config.enabled)
        assertEquals(120L, config.timeoutSeconds)
        assertEquals("filesystem tools", config.description)
        assertEquals(McpExposure.Direct, config.exposure)
        assertEquals(
            mapOf("read_*" to McpExposure.Hidden, "write" to McpExposure.Direct),
            config.toolExposure,
        )
    }

    @Test
    fun infersHttpFromUrl() {
        val result = parse(
            """{"mcpServers":{"docs":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer x"}}}}""",
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val config = result.servers.single().second as McpServerConfig.Http
        assertEquals("https://example.com/mcp", config.url)
        assertEquals(mapOf("Authorization" to "Bearer x"), config.headers)
        assertNull(config.oauth)
        assertNull(config.authProvider)
        assertEquals(McpExposure.Deferred, config.exposure)
        assertEquals(60L, config.timeoutSeconds)
    }

    @Test
    fun parsesHttpOauthAndAuthProvider() {
        val result = parse(
            """
            {"mcpServers":{"remote":{
              "type":"http",
              "url":"https://mcp.example.com/rpc",
              "oauth":{
                "clientId":"client-1",
                "clientSecret":"secret",
                "callbackPort":8080,
                "callbackUrl":"http://localhost:8080/oauth/callback",
                "scope":"read write",
                "clientName":"my-client",
                "clientRegistration":"dcr",
                "authServerMetadataUrl":"https://auth.example.com/.well-known/oauth-authorization-server"
              },
              "auth":{"provider":"github"}
            }}}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val config = result.servers.single().second as McpServerConfig.Http
        val oauth = config.oauth!!
        assertEquals("client-1", oauth.clientId)
        assertEquals("secret", oauth.clientSecret)
        assertEquals(8080, oauth.callbackPort)
        assertEquals("http://localhost:8080/oauth/callback", oauth.callbackUrl)
        assertEquals("read write", oauth.scope)
        assertEquals("my-client", oauth.clientName)
        assertEquals("dcr", oauth.clientRegistration)
        assertEquals("https://auth.example.com/.well-known/oauth-authorization-server", oauth.authServerMetadataUrl)
        assertEquals("github", config.authProvider)
    }

    @Test
    fun rejectsSseTransport() {
        val result = parse("""{"mcpServers":{"old":{"type":"sse","url":"https://example.com/sse"}}}""")
        assertEquals(0, result.servers.size)
        assertHasError(result, "legacy SSE transport is not supported")
    }

    @Test
    fun rejectsInvalidServerName() {
        val result = parse("""{"mcpServers":{"bad name":{"command":"x"}}}""")
        assertEquals(0, result.servers.size)
        assertHasError(result, "invalid server name")
    }

    @Test
    fun rejectsUnknownType() {
        val result = parse("""{"mcpServers":{"s":{"type":"websocket","command":"x"}}}""")
        assertEquals(0, result.servers.size)
        assertHasError(result, "type", "must be \"stdio\" or \"http\"")
    }

    @Test
    fun rejectsMissingCommandAndUrl() {
        val result = parse("""{"mcpServers":{"s":{"enabled":true}}}""")
        assertEquals(0, result.servers.size)
        assertHasError(result, "needs either \"command\" (stdio) or \"url\"")
    }

    @Test
    fun collectsErrorsAcrossServersAndKeepsValidOnes() {
        val result = parse(
            """
            {"mcpServers":{
              "good":{"command":"x"},
              "bad name":{"command":"y"},
              "broken":{"url":"ftp://example.com"}
            }}
            """.trimIndent(),
        )
        assertEquals(listOf("good"), result.servers.map { it.first })
        assertHasError(result, "invalid server name", "must be an http or https URL")
    }

    @Test
    fun validatesTimeoutEnabledArgsAndEnv() {
        val result = parse(
            """
            {"mcpServers":{"s":{
              "command":"x",
              "enabled":"yes",
              "timeout":0,
              "args":[1],
              "env":{"A":1}
            }}}
            """.trimIndent(),
        )
        assertEquals(0, result.servers.size)
        assertHasError(
            result,
            "mcpServers.s.enabled: must be a boolean",
            "mcpServers.s.timeout: timeout must be a positive number of seconds",
            "mcpServers.s.args: must be an array of strings",
            "mcpServers.s.env: must map names to strings",
        )
    }

    @Test
    fun defaultsExposureToDeferredAndRejectsUnknownExposure() {
        val valid = parse("""{"mcpServers":{"s":{"command":"x","exposure":"codemode"}}}""")
        assertEquals(McpExposure.Codemode, (valid.servers.single().second as McpServerConfig.Stdio).exposure)
        val alias = parse("""{"mcpServers":{"s":{"command":"x","exposure":"codemode-deferred"}}}""")
        assertEquals(McpExposure.Codemode, (alias.servers.single().second as McpServerConfig.Stdio).exposure)
        val invalid = parse("""{"mcpServers":{"s":{"command":"x","exposure":"sometimes"}}}""")
        assertEquals(0, invalid.servers.size)
        assertHasError(invalid, "mcpServers.s.exposure: exposure must be one of")
    }

    @Test
    fun validatesToolExposureValues() {
        val valid = parse(
            """{"mcpServers":{"s":{"command":"x","toolExposure":{"read_*":"hidden","write":"direct"}}}}""",
        )
        assertEquals(
            mapOf("read_*" to McpExposure.Hidden, "write" to McpExposure.Direct),
            (valid.servers.single().second as McpServerConfig.Stdio).toolExposure,
        )
        val invalid = parse(
            """{"mcpServers":{"s":{"command":"x","toolExposure":{"write":"bogus"}}}}""",
        )
        assertEquals(0, invalid.servers.size)
        assertHasError(invalid, "mcpServers.s.toolExposure.write: exposure must be one of")
    }

    @Test
    fun validatesOauthCallbackUrl() {
        val invalidHttps = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"callbackUrl":"https://localhost:8080/callback"}}}}""",
        )
        assertHasError(invalidHttps, "mcpServers.s.oauth.callbackUrl: must be an http URI on localhost")

        val invalidHost = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"callbackUrl":"http://example.com/callback"}}}}""",
        )
        assertHasError(invalidHost, "must be an http URI on localhost")

        val valid = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"callbackUrl":"http://127.0.0.1:9000/callback"}}}}""",
        )
        assertTrue(valid.errors.isEmpty(), valid.errors.toString())
        assertEquals(
            "http://127.0.0.1:9000/callback",
            (valid.servers.single().second as McpServerConfig.Http).oauth!!.callbackUrl,
        )
    }

    @Test
    fun validatesOauthCallbackPortMismatch() {
        val result = parse(
            """
            {"mcpServers":{"s":{"url":"https://example.com","oauth":{
              "callbackPort":9090,
              "callbackUrl":"http://localhost:8080/callback"
            }}}}
            """.trimIndent(),
        )
        assertHasError(result, "oauth.callbackUrl and oauth.callbackPort name different ports")
    }

    @Test
    fun validatesAuthServerMetadataUrl() {
        val externalHttp = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"authServerMetadataUrl":"http://auth.example.com/meta"}}}}""",
        )
        assertHasError(externalHttp, "authServerMetadataUrl", "must be an https URL")

        val loopbackHttp = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"authServerMetadataUrl":"http://127.0.0.1:9000/meta"}}}}""",
        )
        assertTrue(loopbackHttp.errors.isEmpty(), loopbackHttp.errors.toString())

        val https = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","oauth":{"authServerMetadataUrl":"https://auth.example.com/meta"}}}}""",
        )
        assertTrue(https.errors.isEmpty(), https.errors.toString())
    }

    @Test
    fun requiresHttpsForAuthProviderExceptLoopback() {
        val insecure = parse(
            """{"mcpServers":{"s":{"url":"http://example.com","auth":{"provider":"github"}}}}""",
        )
        assertHasError(insecure, "auth requires an https URL")

        val loopback = parse(
            """{"mcpServers":{"s":{"url":"http://localhost:8080","auth":{"provider":"github"}}}}""",
        )
        assertTrue(loopback.errors.isEmpty(), loopback.errors.toString())
        assertEquals("github", (loopback.servers.single().second as McpServerConfig.Http).authProvider)

        val secure = parse(
            """{"mcpServers":{"s":{"url":"https://example.com","auth":{"provider":"github"}}}}""",
        )
        assertTrue(secure.errors.isEmpty(), secure.errors.toString())
    }

    @Test
    fun validatesCimdClientRegistration() {
        val combined = parse(
            """
            {"mcpServers":{"s":{"url":"https://example.com","oauth":{
              "clientRegistration":"cimd",
              "clientId":"client"
            }}}}
            """.trimIndent(),
        )
        assertHasError(combined, "cannot be combined with oauth.clientId")

        val wrongPath = parse(
            """
            {"mcpServers":{"s":{"url":"https://example.com","oauth":{
              "clientRegistration":"cimd",
              "callbackUrl":"http://localhost:8080/other"
            }}}}
            """.trimIndent(),
        )
        assertHasError(wrongPath, "requires oauth.callbackUrl on localhost or 127.0.0.1 with path /callback")

        val valid = parse(
            """
            {"mcpServers":{"s":{"url":"https://example.com","oauth":{
              "clientRegistration":"cimd",
              "callbackUrl":"http://localhost:8080/callback"
            }}}}
            """.trimIndent(),
        )
        assertTrue(valid.errors.isEmpty(), valid.errors.toString())
    }

    @Test
    fun ignoresUnknownFields() {
        val result = parse(
            """{"mcpServers":{"s":{"command":"x","unknownField":true,"extra":{"nested":1}}}}""",
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("x", (result.servers.single().second as McpServerConfig.Stdio).command)
    }

    @Test
    fun rejectsServerNameConflicts() {
        val result = parse(
            """{"mcpServers":{"foo-bar":{"command":"x"},"foo_bar":{"command":"y"}}}""",
        )
        assertEquals(1, result.servers.size)
        assertHasError(result, "server name conflicts")
    }

    @Test
    fun missingOrInvalidMcpServersObject() {
        val empty = parse("{}")
        assertTrue(empty.errors.isEmpty())
        assertTrue(empty.servers.isEmpty())
        val invalid = parse("""{"mcpServers":[]}""")
        assertHasError(invalid, "mcpServers must be an object")
        assertTrue(invalid.servers.isEmpty())
    }

    @Test
    fun parseMcpServersThrowsAllErrors() {
        val root = Json.parseToJsonElement(
            """{"mcpServers":{"a":{"type":"sse","url":"https://x"},"b":{"type":"nope"}}}""",
        ).jsonObject
        val error = assertFailsWith<McpConfigException> { parseMcpServers(root) }
        assertEquals(2, error.errors.size)
    }

    @Test
    fun appliesOverrideKeysOnly() {
        val base = McpServerConfig.Stdio(
            command = "npx",
            args = listOf("server"),
            timeoutSeconds = 120,
            description = "desc",
        )
        val merged = applyMcpServerOverrides(
            base,
            Json.parseToJsonElement(
                """{"enabled":false,"exposure":"hidden","toolExposure":{"read":"direct"}}""",
            ).jsonObject,
        )
        assertTrue(merged.errors.isEmpty(), merged.errors.toString())
        val config = merged.config as McpServerConfig.Stdio
        assertEquals(false, config.enabled)
        assertEquals(McpExposure.Hidden, config.exposure)
        assertEquals(mapOf("read" to McpExposure.Direct), config.toolExposure)
        assertEquals("npx", config.command)
        assertEquals(listOf("server"), config.args)
        assertEquals(120L, config.timeoutSeconds)
        assertEquals("desc", config.description)
    }

    @Test
    fun overrideRejectsOtherKeysAndInvalidValues() {
        val base = McpServerConfig.Http(url = "https://example.com")
        val definition = applyMcpServerOverrides(
            base,
            Json.parseToJsonElement("""{"command":"npx"}""").jsonObject,
        )
        assertNull(definition.config)
        assertTrue(definition.errors.single().contains("may only override"))

        val invalid = applyMcpServerOverrides(
            base,
            Json.parseToJsonElement("""{"enabled":"yes","exposure":"sometimes"}""").jsonObject,
        )
        assertNull(invalid.config)
        assertEquals(2, invalid.errors.size)
    }
}
