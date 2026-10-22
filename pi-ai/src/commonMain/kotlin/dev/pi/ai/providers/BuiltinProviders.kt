package dev.pi.ai.providers

import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Model
import dev.pi.ai.ModelCatalog
import dev.pi.ai.Provider
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.TranscriptContext
import dev.pi.ai.api.ApiProvider
import dev.pi.ai.api.anthropicMessagesApi
import dev.pi.ai.api.googleGenerativeAIApi
import dev.pi.ai.api.googleVertexApi
import dev.pi.ai.api.openAICompletionsApi
import dev.pi.ai.api.openAIResponsesApi
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CompositeAuth
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.EnvApiKeyAuth
import dev.pi.ai.auth.StoredApiKeyAuth
import dev.pi.ai.auth.oauth.bundledOAuthFlows
import dev.pi.ai.auth.oauth.oauthResolver
import dev.pi.ai.http.HttpTransport

private const val OPENAI_COMPLETIONS_API = "openai-completions"
private const val OPENAI_RESPONSES_API = "openai-responses"
private const val ANTHROPIC_MESSAGES_API = "anthropic-messages"
private const val GOOGLE_GENERATIVE_AI_API = "google-generative-ai"
private const val GOOGLE_VERTEX_API = "google-vertex"

fun builtinProviders(
    transport: HttpTransport,
    credentialStore: CredentialStore? = null,
): List<Provider> {
    val oauthFlows = bundledOAuthFlows()
    return BUILTIN_PROVIDER_SPECS.map { spec ->
        val allModels = ModelCatalog.findByProvider(spec.id)
        val chatModels = allModels.filterIsInstance<Model>()
        val apis = allModels.map { it.api }.distinct()
        Provider(
            id = spec.id,
            name = spec.name,
            baseUrl = spec.baseUrl,
            headers = null,
            apiProviders = apis.associateWith { api -> apiFactory(api, transport) },
            models = chatModels,
            allModels = allModels,
            auth = providerAuth(spec, oauthFlows, credentialStore, transport),
        )
    }
}

private fun providerAuth(
    spec: BuiltinProviderSpec,
    oauthFlows: Map<String, dev.pi.ai.auth.oauth.OAuthAuth>,
    credentialStore: CredentialStore?,
    transport: HttpTransport,
): AuthResolver {
    if (credentialStore == null) return EnvApiKeyAuth(spec.authLabel, spec.envVars)
    val resolvers = buildList {
        oauthFlows[spec.id]?.let { flow -> add(oauthResolver(spec.id, flow, credentialStore, transport)) }
        add(StoredApiKeyAuth(spec.id, credentialStore, spec.envVars))
    }
    return CompositeAuth(resolvers)
}

private fun apiFactory(api: String, transport: HttpTransport): () -> ApiProvider = when (api) {
    OPENAI_COMPLETIONS_API -> ({ openAICompletionsApi(transport) })
    OPENAI_RESPONSES_API -> ({ openAIResponsesApi(transport) })
    ANTHROPIC_MESSAGES_API -> ({ anthropicMessagesApi(transport) })
    GOOGLE_GENERATIVE_AI_API -> ({ googleGenerativeAIApi(transport) })
    GOOGLE_VERTEX_API -> ({ googleVertexApi(transport) })
    else -> ({ UnimplementedApiProvider(api) })
}

private class UnimplementedApiProvider(override val api: String) : ApiProvider {
    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = throw NotImplementedError("Provider API \"$api\" is not implemented")

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = throw NotImplementedError("Provider API \"$api\" is not implemented")
}

private class BuiltinProviderSpec(
    val id: String,
    val name: String,
    val baseUrl: String?,
    val authLabel: String,
    val envVars: List<String>,
)

private val BUILTIN_PROVIDER_SPECS = listOf(
    BuiltinProviderSpec(
        id = "amazon-bedrock",
        name = "Amazon Bedrock",
        baseUrl = null,
        authLabel = "AWS credentials or bearer token",
        envVars = listOf(
            "AWS_BEARER_TOKEN_BEDROCK",
            "AWS_PROFILE",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI",
            "AWS_WEB_IDENTITY_TOKEN_FILE",
        ),
    ),
    BuiltinProviderSpec(
        id = "ant-ling",
        name = "Ant Ling",
        baseUrl = "https://api.ant-ling.com/v1",
        authLabel = "Ant Ling API key",
        envVars = listOf("ANT_LING_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "anthropic",
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        authLabel = "Anthropic API key",
        envVars = listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "azure",
        name = "Azure",
        baseUrl = null,
        authLabel = "Azure OpenAI API key",
        envVars = listOf("AZURE_OPENAI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "baseten",
        name = "Baseten",
        baseUrl = "https://inference.baseten.co/v1",
        authLabel = "Baseten API key",
        envVars = listOf("BASETEN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "cerebras",
        name = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        authLabel = "Cerebras API key",
        envVars = listOf("CEREBRAS_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "cloudflare-ai-gateway",
        name = "Cloudflare AI Gateway",
        baseUrl = null,
        authLabel = "Cloudflare API key",
        envVars = listOf("CLOUDFLARE_API_KEY", "CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_GATEWAY_ID"),
    ),
    BuiltinProviderSpec(
        id = "cloudflare-workers-ai",
        name = "Cloudflare Workers AI",
        baseUrl = null,
        authLabel = "Cloudflare API key",
        envVars = listOf("CLOUDFLARE_API_KEY", "CLOUDFLARE_ACCOUNT_ID"),
    ),
    BuiltinProviderSpec(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        authLabel = "DeepSeek API key",
        envVars = listOf("DEEPSEEK_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "fireworks",
        name = "Fireworks",
        baseUrl = "https://api.fireworks.ai/inference",
        authLabel = "Fireworks API key",
        envVars = listOf("FIREWORKS_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "github-copilot",
        name = "GitHub Copilot",
        baseUrl = "https://api.individual.githubcopilot.com",
        authLabel = "GitHub Copilot token",
        envVars = listOf("COPILOT_GITHUB_TOKEN"),
    ),
    BuiltinProviderSpec(
        id = "google",
        name = "Google",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        authLabel = "Gemini API key",
        envVars = listOf("GEMINI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "google-vertex",
        name = "Google Vertex AI",
        baseUrl = null,
        authLabel = "Google Cloud credentials",
        envVars = listOf("GOOGLE_CLOUD_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS"),
    ),
    BuiltinProviderSpec(
        id = "groq",
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        authLabel = "Groq API key",
        envVars = listOf("GROQ_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "huggingface",
        name = "Hugging Face",
        baseUrl = "https://router.huggingface.co/v1",
        authLabel = "Hugging Face token",
        envVars = listOf("HF_TOKEN"),
    ),
    BuiltinProviderSpec(
        id = "kimi-coding",
        name = "Kimi For Coding",
        baseUrl = "https://api.kimi.com/coding/v1",
        authLabel = "Kimi API key",
        envVars = listOf("KIMI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "meta",
        name = "Meta",
        baseUrl = "https://api.meta.ai/v1",
        authLabel = "Meta Model API key",
        envVars = listOf("META_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "minimax",
        name = "MiniMax",
        baseUrl = "https://api.minimax.io/anthropic",
        authLabel = "MiniMax API key",
        envVars = listOf("MINIMAX_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "minimax-cn",
        name = "MiniMax CN",
        baseUrl = "https://api.minimaxi.com/anthropic",
        authLabel = "MiniMax CN API key",
        envVars = listOf("MINIMAX_CN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "mistral",
        name = "Mistral",
        baseUrl = "https://api.mistral.ai",
        authLabel = "Mistral API key",
        envVars = listOf("MISTRAL_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "moonshotai",
        name = "Moonshot AI",
        baseUrl = "https://api.moonshot.ai/v1",
        authLabel = "Moonshot AI API key",
        envVars = listOf("MOONSHOT_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "moonshotai-cn",
        name = "Moonshot AI CN",
        baseUrl = "https://api.moonshot.cn/v1",
        authLabel = "Moonshot AI API key",
        envVars = listOf("MOONSHOT_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "nvidia",
        name = "NVIDIA",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        authLabel = "NVIDIA API key",
        envVars = listOf("NVIDIA_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        authLabel = "OpenAI API key",
        envVars = listOf("OPENAI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "openai-codex",
        name = "OpenAI Codex (legacy)",
        baseUrl = "https://chatgpt.com/backend-api",
        authLabel = "OpenAI (ChatGPT Plus/Pro)",
        envVars = emptyList(),
    ),
    BuiltinProviderSpec(
        id = "opencode",
        name = "OpenCode Zen",
        baseUrl = null,
        authLabel = "OpenCode API key",
        envVars = listOf("OPENCODE_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "opencode-go",
        name = "OpenCode Go",
        baseUrl = null,
        authLabel = "OpenCode API key",
        envVars = listOf("OPENCODE_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        authLabel = "OpenRouter API key",
        envVars = listOf("OPENROUTER_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "qwen-token-plan",
        name = "Qwen Token Plan",
        baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
        authLabel = "Qwen Token Plan API key",
        envVars = listOf("QWEN_TOKEN_PLAN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "qwen-token-plan-cn",
        name = "Qwen Token Plan CN",
        baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
        authLabel = "Qwen Token Plan CN API key",
        envVars = listOf("QWEN_TOKEN_PLAN_CN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "qwen-token-plan-individual",
        name = "Qwen Token Plan Individual",
        baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
        authLabel = "Qwen Token Plan Individual API key",
        envVars = listOf("QWEN_TOKEN_PLAN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "radius",
        name = "Radius",
        baseUrl = null,
        authLabel = "Radius API key",
        envVars = listOf("RADIUS_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "together",
        name = "Together",
        baseUrl = "https://api.together.ai/v1",
        authLabel = "Together API key",
        envVars = listOf("TOGETHER_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "typesafe",
        name = "TypeSafe",
        baseUrl = null,
        authLabel = "TypeSafe API key",
        envVars = listOf("TYPESAFE_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "vercel-ai-gateway",
        name = "Vercel AI Gateway",
        baseUrl = "https://ai-gateway.vercel.sh",
        authLabel = "Vercel AI Gateway API key",
        envVars = listOf("AI_GATEWAY_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "xai",
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        authLabel = "xAI API key",
        envVars = listOf("XAI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "xiaomi",
        name = "Xiaomi",
        baseUrl = "https://api.xiaomimimo.com/v1",
        authLabel = "Xiaomi API key",
        envVars = listOf("XIAOMI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "xiaomi-token-plan-ams",
        name = "Xiaomi Token Plan AMS",
        baseUrl = "https://token-plan-ams.xiaomimimo.com/v1",
        authLabel = "Xiaomi Token Plan AMS API key",
        envVars = listOf("XIAOMI_TOKEN_PLAN_AMS_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "xiaomi-token-plan-cn",
        name = "Xiaomi Token Plan CN",
        baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        authLabel = "Xiaomi Token Plan CN API key",
        envVars = listOf("XIAOMI_TOKEN_PLAN_CN_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "xiaomi-token-plan-sgp",
        name = "Xiaomi Token Plan SGP",
        baseUrl = "https://token-plan-sgp.xiaomimimo.com/v1",
        authLabel = "Xiaomi Token Plan SGP API key",
        envVars = listOf("XIAOMI_TOKEN_PLAN_SGP_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "zai",
        name = "Z.AI",
        baseUrl = "https://api.z.ai/api/coding/paas/v4",
        authLabel = "Z.AI API key",
        envVars = listOf("ZAI_API_KEY"),
    ),
    BuiltinProviderSpec(
        id = "zai-coding-cn",
        name = "Z.AI Coding CN",
        baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
        authLabel = "Z.AI Coding CN API key",
        envVars = listOf("ZAI_CODING_CN_API_KEY"),
    ),
)
