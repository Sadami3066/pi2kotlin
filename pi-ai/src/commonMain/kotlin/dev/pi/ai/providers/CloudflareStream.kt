package dev.pi.ai.providers

import dev.pi.ai.AnyModel
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ImageModel
import dev.pi.ai.Model
import dev.pi.ai.auth.ApiKeyCredential
import dev.pi.ai.auth.CredentialStore

const val CLOUDFLARE_API_KEY_ENV: String = "CLOUDFLARE_API_KEY"
const val CLOUDFLARE_ACCOUNT_ID_ENV: String = "CLOUDFLARE_ACCOUNT_ID"
const val CLOUDFLARE_GATEWAY_ID_ENV: String = "CLOUDFLARE_GATEWAY_ID"
const val CLOUDFLARE_GATEWAY_TOKEN_ENV: String = "CLOUDFLARE_GATEWAY_TOKEN"

private const val CLOUDFLARE_ACCOUNT_ID_PLACEHOLDER = "{CLOUDFLARE_ACCOUNT_ID}"
private const val CLOUDFLARE_GATEWAY_ID_PLACEHOLDER = "{CLOUDFLARE_GATEWAY_ID}"

fun replaceCloudflarePlaceholders(baseUrl: String, env: Map<String, String>?): String {
    var resolved = baseUrl
    val accountId = env?.get(CLOUDFLARE_ACCOUNT_ID_ENV)
    if (!accountId.isNullOrEmpty()) resolved = resolved.replace(CLOUDFLARE_ACCOUNT_ID_PLACEHOLDER, accountId)
    val gatewayId = env?.get(CLOUDFLARE_GATEWAY_ID_ENV)
    if (!gatewayId.isNullOrEmpty()) resolved = resolved.replace(CLOUDFLARE_GATEWAY_ID_PLACEHOLDER, gatewayId)
    return resolved
}

fun resolveCloudflareModel(model: AnyModel, env: Map<String, String>?): AnyModel {
    if (env.isNullOrEmpty()) return model
    val resolvedBaseUrl = replaceCloudflarePlaceholders(model.baseUrl, env)
    if (resolvedBaseUrl == model.baseUrl) return model
    return when (model) {
        is Model -> model.copy(baseUrl = resolvedBaseUrl)
        is ImageModel -> model.copy(baseUrl = resolvedBaseUrl)
        is ClassifierModel -> model.copy(baseUrl = resolvedBaseUrl)
    }
}

fun cloudflareRequestEnv(
    credentialStore: CredentialStore?,
    providerId: String,
    requestEnv: Map<String, String>?,
): Map<String, String>? {
    val credentialEnv = (credentialStore?.read(providerId) as? ApiKeyCredential)?.env
    if (credentialEnv.isNullOrEmpty() && requestEnv.isNullOrEmpty()) return null
    val merged = LinkedHashMap<String, String>()
    credentialEnv?.forEach { (name, value) -> merged[name] = value }
    requestEnv?.forEach { (name, value) -> merged[name] = value }
    return merged.ifEmpty { null }
}

fun cloudflareModelTransform(
    credentialStore: CredentialStore?,
    providerId: String,
): (AnyModel, Map<String, String>?) -> AnyModel = { model, env ->
    resolveCloudflareModel(model, cloudflareRequestEnv(credentialStore, providerId, env))
}
