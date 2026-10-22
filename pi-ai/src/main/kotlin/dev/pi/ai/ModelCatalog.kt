package dev.pi.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ModelCatalog {
    private const val INDEX_RESOURCE = "/models/index.txt"
    private const val SCHEMA_VERSION = 6

    val models: List<AnyModel> by lazy { loadModels() }

    val providers: List<String> by lazy { models.map { it.provider }.distinct().sorted() }

    val manifest: Manifest by lazy { loadManifest() }

    data class Manifest(val schemaVersion: Int)

    fun modelsOfType(type: ModelType): List<AnyModel> = models.filter { it.modelType == type }

    fun findByProvider(provider: String): List<AnyModel> = models.filter { it.provider == provider }

    fun find(provider: String, id: String, type: ModelType = ModelType.Chat): AnyModel? =
        models.firstOrNull { it.provider == provider && it.id == id && it.modelType == type }

    fun findChat(provider: String, id: String): Model? = find(provider, id, ModelType.Chat) as? Model

    private fun loadManifest(): Manifest {
        val text = readResource("/models/manifest.json")
        val schema = PiJson.parseToJsonElement(text).jsonObject["schemaVersion"]!!.jsonPrimitive.content.toInt()
        require(schema == SCHEMA_VERSION) { "unsupported model catalog schema $schema, expected $SCHEMA_VERSION" }
        return Manifest(schema)
    }

    private fun loadModels(): List<AnyModel> {
        val files = readResource(INDEX_RESOURCE).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val result = ArrayList<AnyModel>()
        for (file in files) {
            val root = PiJson.parseToJsonElement(readResource("/models/$file")).jsonObject
            for ((_, apiEntries) in root) {
                for ((key, value) in apiEntries.jsonObject) {
                    val model = parseEntry(key, value.jsonObject) ?: continue
                    result += model
                }
            }
        }
        return result
    }

    private fun parseEntry(key: String, entry: JsonObject): AnyModel? {
        val type = when {
            key.startsWith("image:") -> ModelType.Image
            key.startsWith("classifier:") -> ModelType.Classifier
            key.startsWith("chat:") -> ModelType.Chat
            else -> when (entry["type"]?.jsonPrimitive?.contentOrNull()) {
                "image" -> ModelType.Image
                "classifier" -> ModelType.Classifier
                else -> ModelType.Chat
            }
        }
        return when (type) {
            ModelType.Chat -> PiJson.decodeFromJsonElement(Model.serializer(), entry)
            ModelType.Image -> PiJson.decodeFromJsonElement(ImageModel.serializer(), entry)
            ModelType.Classifier -> PiJson.decodeFromJsonElement(ClassifierModel.serializer(), entry)
        }
    }

    private fun readResource(path: String): String =
        ModelCatalog::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing model catalog resource: $path")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null
}
