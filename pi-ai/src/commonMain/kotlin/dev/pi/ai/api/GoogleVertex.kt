package dev.pi.ai.api

import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Model
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
import dev.pi.ai.http.HttpTransport

class GoogleVertexApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = GOOGLE_VERTEX_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(model, context, options, null, null)

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val base = buildBaseOptions(model, context, options)
        val toolChoice = options.toolChoice?.let { if (it == ToolChoice.None) "none" else "auto" }
        return startStream(model, context, base, toolChoice, resolveGoogleSimpleThinking(model, options))
    }

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        toolChoice: String?,
        thinking: GoogleThinkingOptions?,
    ): AssistantMessageEventStream = streamGoogle(
        transport = transport,
        model = model,
        context = context,
        options = options,
        toolChoice = toolChoice,
        thinking = thinking,
        resolveUrlAndHeaders = { resolveVertexUrlAndHeaders(model, options) },
        endLabel = "Google Vertex",
    )
}

private fun resolveVertexUrlAndHeaders(model: Model, options: StreamOptions): Pair<String, Map<String, String>> {
    val credentials = resolveGoogleVertexApiKey(options.apiKey)
        ?: throw GoogleStreamException(
            "Vertex AI requires credentials. Pass a Google Cloud API key or an OAuth access token as apiKey.",
        )
    val credentialMode = if (isGoogleApiKey(credentials)) {
        GoogleVertexCredentialMode.ApiKey
    } else {
        GoogleVertexCredentialMode.BearerToken
    }
    var project: String? = null
    var location: String? = null
    if (credentialMode == GoogleVertexCredentialMode.BearerToken) {
        project = resolveGoogleVertexProject(options)
            ?: throw GoogleStreamException(
                "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT or pass project in options.",
            )
        location = resolveGoogleVertexLocation(options)
            ?: throw GoogleStreamException(
                "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION or pass location in options.",
            )
    }
    val headers = buildGoogleBaseHeaders(model, options)
    if (credentialMode == GoogleVertexCredentialMode.ApiKey) {
        if (!hasGoogleHeaderName(headers, "x-goog-api-key")) headers["x-goog-api-key"] = credentials
    } else {
        if (!hasGoogleHeaderName(headers, "authorization")) headers["Authorization"] = "Bearer $credentials"
    }
    val url = googleVertexUrl(model, credentialMode, project, location)
    return url to finishGoogleHeaders(headers)
}

fun googleVertexApi(transport: HttpTransport): ApiProvider = GoogleVertexApi(transport)
