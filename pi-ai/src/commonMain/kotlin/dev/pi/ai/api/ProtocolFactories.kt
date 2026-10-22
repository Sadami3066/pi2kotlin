package dev.pi.ai.api

import dev.pi.ai.http.HttpTransport

const val OPENAI_COMPLETIONS_CHAT_API: String = "openai-completions"
const val OPENAI_RESPONSES_CHAT_API: String = "openai-responses"
const val OPENAI_CODEX_RESPONSES_CHAT_API: String = "openai-codex-responses"
const val AZURE_OPENAI_RESPONSES_CHAT_API: String = "azure-openai-responses"
const val ANTHROPIC_MESSAGES_CHAT_API: String = "anthropic-messages"
const val GOOGLE_GENERATIVE_AI_CHAT_API: String = "google-generative-ai"
const val GOOGLE_VERTEX_CHAT_API: String = "google-vertex"
const val MISTRAL_CONVERSATIONS_CHAT_API: String = "mistral-conversations"
const val BEDROCK_CONVERSE_STREAM_CHAT_API: String = "bedrock-converse-stream"
const val PI_MESSAGES_CHAT_API: String = "pi-messages"

val CHAT_APIS: List<String> = listOf(
    OPENAI_COMPLETIONS_CHAT_API,
    OPENAI_RESPONSES_CHAT_API,
    OPENAI_CODEX_RESPONSES_CHAT_API,
    AZURE_OPENAI_RESPONSES_CHAT_API,
    ANTHROPIC_MESSAGES_CHAT_API,
    GOOGLE_GENERATIVE_AI_CHAT_API,
    GOOGLE_VERTEX_CHAT_API,
    MISTRAL_CONVERSATIONS_CHAT_API,
    BEDROCK_CONVERSE_STREAM_CHAT_API,
    PI_MESSAGES_CHAT_API,
)

fun chatApiFactory(api: String, transport: HttpTransport): (() -> ApiProvider)? = when (api) {
    OPENAI_COMPLETIONS_CHAT_API -> ({ openAICompletionsApi(transport) })
    OPENAI_RESPONSES_CHAT_API -> ({ openAIResponsesApi(transport) })
    OPENAI_CODEX_RESPONSES_CHAT_API -> ({ openAICodexResponsesApi(transport) })
    AZURE_OPENAI_RESPONSES_CHAT_API -> ({ azureOpenAIResponsesApi(transport) })
    ANTHROPIC_MESSAGES_CHAT_API -> ({ anthropicMessagesApi(transport) })
    GOOGLE_GENERATIVE_AI_CHAT_API -> ({ googleGenerativeAIApi(transport) })
    GOOGLE_VERTEX_CHAT_API -> ({ googleVertexApi(transport) })
    MISTRAL_CONVERSATIONS_CHAT_API -> ({ mistralConversationsApi(transport) })
    BEDROCK_CONVERSE_STREAM_CHAT_API -> ({ bedrockConverseStreamApi(transport) })
    PI_MESSAGES_CHAT_API -> ({ piMessagesApi(transport) })
    else -> null
}
