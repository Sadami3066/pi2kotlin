package dev.pi.ai

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import okio.GzipSource
import okio.buffer

object ModelCatalog {
    val models: List<AnyModel> by lazy { loadModels() }

    val providers: List<String> by lazy { models.map { it.provider }.distinct().sorted() }

    val manifest: Manifest = Manifest(CatalogData.SCHEMA_VERSION)

    data class Manifest(val schemaVersion: Int)

    fun modelsOfType(type: ModelType): List<AnyModel> = models.filter { it.modelType == type }

    fun findByProvider(provider: String): List<AnyModel> = models.filter { it.provider == provider }

    fun find(provider: String, id: String, type: ModelType = ModelType.Chat): AnyModel? =
        models.firstOrNull { it.provider == provider && it.id == id && it.modelType == type }

    fun findChat(provider: String, id: String): Model? = find(provider, id, ModelType.Chat) as? Model

    private fun loadBundle(): JsonObject {
        val bytes = Base64.decode(CatalogData.GZIP_BASE64)
        val source = GzipSource(Buffer().write(bytes))
        val text = source.buffer().use { it.readUtf8() }
        return PiJson.parseToJsonElement(text).jsonObject
    }

    private fun loadModels(): List<AnyModel> {
        val files = loadBundle()["files"]!!.jsonObject
        val result = ArrayList<AnyModel>()
        for ((_, providerData) in files) {
            for ((_, apiEntries) in providerData.jsonObject) {
                for ((key, value) in apiEntries.jsonObject) {
                    result += parseEntry(key, value.jsonObject)
                }
            }
        }
        return result
    }

    private fun parseEntry(key: String, entry: JsonObject): AnyModel {
        val type = when {
            key.startsWith("image:") -> ModelType.Image
            key.startsWith("classifier:") -> ModelType.Classifier
            else -> ModelType.Chat
        }
        return when (type) {
            ModelType.Chat -> PiJson.decodeFromJsonElement(Model.serializer(), entry)
            ModelType.Image -> PiJson.decodeFromJsonElement(ImageModel.serializer(), entry)
            ModelType.Classifier -> PiJson.decodeFromJsonElement(ClassifierModel.serializer(), entry)
        }
    }
}
