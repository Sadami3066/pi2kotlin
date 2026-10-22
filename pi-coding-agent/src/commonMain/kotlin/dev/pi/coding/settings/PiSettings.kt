package dev.pi.coding.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SettingsDefaults {
    const val TRANSPORT = "auto"
    const val SESSION_SHARE_DESTINATION = "auto"
    const val STEERING_MODE = "one-at-a-time"
    const val FOLLOW_UP_MODE = "one-at-a-time"
    const val COMPACTION_ENABLED = true
    const val COMPACTION_RESERVE_TOKENS = 16384L
    const val COMPACTION_KEEP_RECENT_TOKENS = 20000L
    const val BRANCH_SUMMARY_RESERVE_TOKENS = 16384L
    const val BRANCH_SUMMARY_SKIP_PROMPT = false
    const val RETRY_ENABLED = true
    const val RETRY_MAX_RETRIES = 3L
    const val RETRY_BASE_DELAY_MS = 2000L
    const val RETRY_MAX_AGENT_DELAY_MS = 60000L
    const val RETRY_PROVIDER_MAX_RETRY_DELAY_MS = 60000L
    const val HIDE_THINKING_BLOCK = false
    const val SHOW_CACHE_MISS_NOTICES = false
    const val DEFAULT_PROJECT_TRUST = "ask"
    const val COLLAPSE_CHANGELOG = false
    const val ENABLE_INSTALL_TELEMETRY = true
    const val ENABLE_ANALYTICS = false
    const val ENABLE_SKILL_COMMANDS = true
    const val TERMINAL_SHOW_IMAGES = true
    const val TERMINAL_IMAGE_WIDTH_CELLS = 60L
    const val TERMINAL_CLEAR_ON_SHRINK = false
    const val TERMINAL_SHOW_TERMINAL_PROGRESS = false
    const val IMAGES_AUTO_RESIZE = true
    const val IMAGES_BLOCK_IMAGES = false
    const val DOUBLE_ESCAPE_ACTION = "tree"
    const val TREE_FILTER_MODE = "default"
    const val EDITOR_PADDING_X = 0L
    const val OUTPUT_PAD = 1L
    const val AUTOCOMPLETE_MAX_VISIBLE = 5L
    const val MARKDOWN_CODE_BLOCK_INDENT = "  "
    const val MARKDOWN_MERMAID = "streaming"
    const val WARNINGS_ANTHROPIC_EXTRA_USAGE = true
    const val CODEMODE_MODE = "on"
    const val CODEMODE_INLINE_BUDGET = 3000L
    const val CACHE_WARMING = "streaming"
    const val TUI_MODE = "fullscreen"
    const val FULLSCREEN_EXIT_OUTPUT = "transcript"
    const val FULLSCREEN_SCROLLBAR = "auto"
    const val FULLSCREEN_COPY_ON_SELECT = true
}

val DEFAULT_TOOL_NAMES: List<String> = listOf("read", "bash", "edit", "write")

fun isToolModifier(entry: String): Boolean = entry.startsWith("+") || entry.startsWith("-")

fun getToolListError(entries: List<String>): String? {
    val modifiers = entries.filter(::isToolModifier)
    if (modifiers.isEmpty()) return null
    if (modifiers.size < entries.size) return "tool names cannot be mixed with +name or -name entries"
    val pattern = modifiers.firstOrNull { it.contains("*") } ?: return null
    return "+name and -name entries take exact tool names, not patterns: $pattern"
}

fun applyToolModifiers(base: List<String>, entries: List<String>): List<String> {
    val tools = base.toMutableList()
    for (entry in entries) {
        if (!isToolModifier(entry)) continue
        val name = entry.drop(1)
        val index = tools.indexOf(name)
        if (entry.startsWith("+") && index == -1 && name.isNotEmpty()) {
            tools.add(name)
        } else if (entry.startsWith("-") && index != -1) {
            tools.removeAt(index)
        }
    }
    return tools
}

fun resolveDefaultTools(entries: List<String>): List<String> {
    val plain = entries.filter { !isToolModifier(it) }
    return applyToolModifiers(if (plain.isNotEmpty() || entries.isEmpty()) plain else DEFAULT_TOOL_NAMES, entries)
}

data class CompactionModelOverride(
    val reserveTokens: Long? = null,
    val keepRecentTokens: Long? = null,
)

data class CompactionSettings(
    val enabled: Boolean = SettingsDefaults.COMPACTION_ENABLED,
    val reserveTokens: Long = SettingsDefaults.COMPACTION_RESERVE_TOKENS,
    val keepRecentTokens: Long = SettingsDefaults.COMPACTION_KEEP_RECENT_TOKENS,
    val modelOverrides: Map<String, CompactionModelOverride>? = null,
)

data class BranchSummarySettings(
    val reserveTokens: Long = SettingsDefaults.BRANCH_SUMMARY_RESERVE_TOKENS,
    val skipPrompt: Boolean = SettingsDefaults.BRANCH_SUMMARY_SKIP_PROMPT,
)

data class ProviderRetrySettings(
    val timeoutMs: Long? = null,
    val maxRetries: Long? = null,
    val maxRetryDelayMs: Long = SettingsDefaults.RETRY_PROVIDER_MAX_RETRY_DELAY_MS,
)

data class RetrySettings(
    val enabled: Boolean = SettingsDefaults.RETRY_ENABLED,
    val maxRetries: Long = SettingsDefaults.RETRY_MAX_RETRIES,
    val baseDelayMs: Long = SettingsDefaults.RETRY_BASE_DELAY_MS,
    val maxAgentDelayMs: Long = SettingsDefaults.RETRY_MAX_AGENT_DELAY_MS,
    val provider: ProviderRetrySettings = ProviderRetrySettings(),
)

data class TerminalSettings(
    val showImages: Boolean = SettingsDefaults.TERMINAL_SHOW_IMAGES,
    val imageWidthCells: Long = SettingsDefaults.TERMINAL_IMAGE_WIDTH_CELLS,
    val clearOnShrink: Boolean = SettingsDefaults.TERMINAL_CLEAR_ON_SHRINK,
    val showTerminalProgress: Boolean = SettingsDefaults.TERMINAL_SHOW_TERMINAL_PROGRESS,
    val hyperlinks: JsonElement? = null,
    val images: JsonElement? = null,
    val trueColor: JsonElement? = null,
)

data class ImageSettings(
    val autoResize: Boolean = SettingsDefaults.IMAGES_AUTO_RESIZE,
    val blockImages: Boolean = SettingsDefaults.IMAGES_BLOCK_IMAGES,
)

data class ThinkingBudgetsSettings(
    val minimal: Long? = null,
    val low: Long? = null,
    val medium: Long? = null,
    val high: Long? = null,
)

data class MarkdownSettings(
    val codeBlockIndent: String = SettingsDefaults.MARKDOWN_CODE_BLOCK_INDENT,
    val mermaid: String = SettingsDefaults.MARKDOWN_MERMAID,
)

data class WarningSettings(
    val anthropicExtraUsage: Boolean = SettingsDefaults.WARNINGS_ANTHROPIC_EXTRA_USAGE,
)

data class CodemodeSettings(
    val mode: String = SettingsDefaults.CODEMODE_MODE,
    val inlineBudget: Long = SettingsDefaults.CODEMODE_INLINE_BUDGET,
)

data class PiSettings(
    val schemaRef: String? = null,
    val lastChangelogVersion: String? = null,
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val modelThinkingLevels: Map<String, String>? = null,
    val transport: String? = null,
    val sessionShareDestination: String? = null,
    val steeringMode: String? = null,
    val followUpMode: String? = null,
    val theme: String? = null,
    val compaction: CompactionSettings? = null,
    val branchSummary: BranchSummarySettings? = null,
    val retry: RetrySettings? = null,
    val hideThinkingBlock: Boolean? = null,
    val showCacheMissNotices: Boolean? = null,
    val externalEditor: String? = null,
    val shellPath: String? = null,
    val quietStartup: JsonElement? = null,
    val defaultProjectTrust: String? = null,
    val shellCommandPrefix: String? = null,
    val npmCommand: List<String>? = null,
    val collapseChangelog: Boolean? = null,
    val enableInstallTelemetry: Boolean? = null,
    val enableAnalytics: Boolean? = null,
    val trackingId: String? = null,
    val packages: List<JsonElement>? = null,
    val extensions: List<String>? = null,
    val deviceId: String? = null,
    val skills: List<String>? = null,
    val prompts: List<String>? = null,
    val themes: List<String>? = null,
    val enableSkillCommands: Boolean? = null,
    val terminal: TerminalSettings? = null,
    val images: ImageSettings? = null,
    val enabledModels: List<String>? = null,
    val defaultTools: List<String>? = null,
    val doubleEscapeAction: String? = null,
    val treeFilterMode: String? = null,
    val thinkingBudgets: ThinkingBudgetsSettings? = null,
    val editorPaddingX: Long? = null,
    val outputPad: Long? = null,
    val autocompleteMaxVisible: Long? = null,
    val showHardwareCursor: Boolean? = null,
    val markdown: MarkdownSettings? = null,
    val warnings: WarningSettings? = null,
    val codemode: CodemodeSettings? = null,
    val sessionDir: String? = null,
    val httpProxy: String? = null,
    val httpIdleTimeoutMs: JsonElement? = null,
    val cacheWarming: String? = null,
    val websocketConnectTimeoutMs: JsonElement? = null,
    val tuiMode: String? = null,
    val fullscreenExitOutput: String? = null,
    val fullscreenScrollbar: String? = null,
    val fullscreenCopyOnSelect: Boolean? = null,
    val fullscreenWheelScrollLines: JsonElement? = null,
    val queueMode: String? = null,
    val websockets: Boolean? = null,
    val extra: JsonObject? = null,
) {
    val modelThinking: Map<String, String>?
        get() = modelThinkingLevels
}

internal object PiSettingsCodec {
    fun decode(raw: JsonObject): PiSettings {
        val reader = FieldReader(raw)
        return PiSettings(
            schemaRef = reader.string("\$schema"),
            lastChangelogVersion = reader.string("lastChangelogVersion"),
            defaultProvider = reader.string("defaultProvider"),
            defaultModel = reader.string("defaultModel"),
            defaultThinkingLevel = reader.string("defaultThinkingLevel"),
            modelThinkingLevels = reader.stringMap("modelThinkingLevels"),
            transport = reader.string("transport"),
            sessionShareDestination = reader.string("sessionShareDestination"),
            steeringMode = reader.string("steeringMode"),
            followUpMode = reader.string("followUpMode"),
            theme = reader.string("theme"),
            compaction = reader.obj("compaction")?.let(::decodeCompaction),
            branchSummary = reader.obj("branchSummary")?.let(::decodeBranchSummary),
            retry = reader.obj("retry")?.let(::decodeRetry),
            hideThinkingBlock = reader.boolean("hideThinkingBlock"),
            showCacheMissNotices = reader.boolean("showCacheMissNotices"),
            externalEditor = reader.string("externalEditor"),
            shellPath = reader.string("shellPath"),
            quietStartup = reader.element("quietStartup"),
            defaultProjectTrust = reader.string("defaultProjectTrust"),
            shellCommandPrefix = reader.string("shellCommandPrefix"),
            npmCommand = reader.strings("npmCommand"),
            collapseChangelog = reader.boolean("collapseChangelog"),
            enableInstallTelemetry = reader.boolean("enableInstallTelemetry"),
            enableAnalytics = reader.boolean("enableAnalytics"),
            trackingId = reader.string("trackingId"),
            packages = reader.arr("packages")?.toList(),
            extensions = reader.strings("extensions"),
            deviceId = reader.string("deviceId"),
            skills = reader.strings("skills"),
            prompts = reader.strings("prompts"),
            themes = reader.strings("themes"),
            enableSkillCommands = reader.boolean("enableSkillCommands"),
            terminal = reader.obj("terminal")?.let(::decodeTerminal),
            images = reader.obj("images")?.let(::decodeImages),
            enabledModels = reader.strings("enabledModels"),
            defaultTools = reader.strings("defaultTools"),
            doubleEscapeAction = reader.string("doubleEscapeAction"),
            treeFilterMode = reader.string("treeFilterMode"),
            thinkingBudgets = reader.obj("thinkingBudgets")?.let(::decodeThinkingBudgets),
            editorPaddingX = reader.long("editorPaddingX"),
            outputPad = reader.long("outputPad"),
            autocompleteMaxVisible = reader.long("autocompleteMaxVisible"),
            showHardwareCursor = reader.boolean("showHardwareCursor"),
            markdown = reader.obj("markdown")?.let(::decodeMarkdown),
            warnings = reader.obj("warnings")?.let(::decodeWarnings),
            codemode = reader.obj("codemode")?.let(::decodeCodemode),
            sessionDir = reader.string("sessionDir"),
            httpProxy = reader.string("httpProxy"),
            httpIdleTimeoutMs = reader.element("httpIdleTimeoutMs"),
            cacheWarming = reader.string("cacheWarming"),
            websocketConnectTimeoutMs = reader.element("websocketConnectTimeoutMs"),
            tuiMode = reader.string("tuiMode"),
            fullscreenExitOutput = reader.string("fullscreenExitOutput"),
            fullscreenScrollbar = reader.string("fullscreenScrollbar"),
            fullscreenCopyOnSelect = reader.boolean("fullscreenCopyOnSelect"),
            fullscreenWheelScrollLines = reader.element("fullscreenWheelScrollLines"),
            queueMode = reader.string("queueMode"),
            websockets = reader.boolean("websockets"),
            extra = raw.unknownFields(),
        )
    }

    private val KNOWN_FIELDS: Set<String> = setOf(
        "\$schema",
        "lastChangelogVersion",
        "defaultProvider",
        "defaultModel",
        "defaultThinkingLevel",
        "modelThinkingLevels",
        "transport",
        "sessionShareDestination",
        "steeringMode",
        "followUpMode",
        "theme",
        "compaction",
        "branchSummary",
        "retry",
        "hideThinkingBlock",
        "showCacheMissNotices",
        "externalEditor",
        "shellPath",
        "quietStartup",
        "defaultProjectTrust",
        "shellCommandPrefix",
        "npmCommand",
        "collapseChangelog",
        "enableInstallTelemetry",
        "enableAnalytics",
        "trackingId",
        "packages",
        "extensions",
        "deviceId",
        "skills",
        "prompts",
        "themes",
        "enableSkillCommands",
        "terminal",
        "images",
        "enabledModels",
        "defaultTools",
        "doubleEscapeAction",
        "treeFilterMode",
        "thinkingBudgets",
        "editorPaddingX",
        "outputPad",
        "autocompleteMaxVisible",
        "showHardwareCursor",
        "markdown",
        "warnings",
        "codemode",
        "sessionDir",
        "httpProxy",
        "httpIdleTimeoutMs",
        "cacheWarming",
        "websocketConnectTimeoutMs",
        "tuiMode",
        "fullscreenExitOutput",
        "fullscreenScrollbar",
        "fullscreenCopyOnSelect",
        "fullscreenWheelScrollLines",
        "queueMode",
        "websockets",
    )

    private fun JsonObject.unknownFields(): JsonObject? {
        val unknown = filterKeys { it !in KNOWN_FIELDS }
        return if (unknown.isEmpty()) null else JsonObject(unknown)
    }

    private fun decodeCompaction(raw: JsonObject): CompactionSettings {
        val reader = FieldReader(raw)
        return CompactionSettings(
            enabled = reader.boolean("enabled") ?: SettingsDefaults.COMPACTION_ENABLED,
            reserveTokens = reader.long("reserveTokens") ?: SettingsDefaults.COMPACTION_RESERVE_TOKENS,
            keepRecentTokens = reader.long("keepRecentTokens") ?: SettingsDefaults.COMPACTION_KEEP_RECENT_TOKENS,
            modelOverrides = reader.obj("modelOverrides")?.let { overrides ->
                overrides.mapNotNull { (key, value) ->
                    (value as? JsonObject)?.let { entry ->
                        val entryReader = FieldReader(entry)
                        key to CompactionModelOverride(
                            reserveTokens = entryReader.long("reserveTokens"),
                            keepRecentTokens = entryReader.long("keepRecentTokens"),
                        )
                    }
                }.toMap()
            },
        )
    }

    private fun decodeBranchSummary(raw: JsonObject): BranchSummarySettings {
        val reader = FieldReader(raw)
        return BranchSummarySettings(
            reserveTokens = reader.long("reserveTokens") ?: SettingsDefaults.BRANCH_SUMMARY_RESERVE_TOKENS,
            skipPrompt = reader.boolean("skipPrompt") ?: SettingsDefaults.BRANCH_SUMMARY_SKIP_PROMPT,
        )
    }

    private fun decodeRetry(raw: JsonObject): RetrySettings {
        val reader = FieldReader(raw)
        return RetrySettings(
            enabled = reader.boolean("enabled") ?: SettingsDefaults.RETRY_ENABLED,
            maxRetries = reader.long("maxRetries") ?: SettingsDefaults.RETRY_MAX_RETRIES,
            baseDelayMs = reader.long("baseDelayMs") ?: SettingsDefaults.RETRY_BASE_DELAY_MS,
            maxAgentDelayMs = reader.long("maxAgentDelayMs") ?: SettingsDefaults.RETRY_MAX_AGENT_DELAY_MS,
            provider = reader.obj("provider")?.let(::decodeProviderRetry) ?: ProviderRetrySettings(),
        )
    }

    private fun decodeProviderRetry(raw: JsonObject): ProviderRetrySettings {
        val reader = FieldReader(raw)
        return ProviderRetrySettings(
            timeoutMs = reader.long("timeoutMs"),
            maxRetries = reader.long("maxRetries"),
            maxRetryDelayMs = reader.long("maxRetryDelayMs") ?: SettingsDefaults.RETRY_PROVIDER_MAX_RETRY_DELAY_MS,
        )
    }

    private fun decodeTerminal(raw: JsonObject): TerminalSettings {
        val reader = FieldReader(raw)
        return TerminalSettings(
            showImages = reader.boolean("showImages") ?: SettingsDefaults.TERMINAL_SHOW_IMAGES,
            imageWidthCells = reader.long("imageWidthCells") ?: SettingsDefaults.TERMINAL_IMAGE_WIDTH_CELLS,
            clearOnShrink = reader.boolean("clearOnShrink") ?: SettingsDefaults.TERMINAL_CLEAR_ON_SHRINK,
            showTerminalProgress = reader.boolean("showTerminalProgress") ?: SettingsDefaults.TERMINAL_SHOW_TERMINAL_PROGRESS,
            hyperlinks = reader.element("hyperlinks"),
            images = reader.element("images"),
            trueColor = reader.element("trueColor"),
        )
    }

    private fun decodeImages(raw: JsonObject): ImageSettings {
        val reader = FieldReader(raw)
        return ImageSettings(
            autoResize = reader.boolean("autoResize") ?: SettingsDefaults.IMAGES_AUTO_RESIZE,
            blockImages = reader.boolean("blockImages") ?: SettingsDefaults.IMAGES_BLOCK_IMAGES,
        )
    }

    private fun decodeThinkingBudgets(raw: JsonObject): ThinkingBudgetsSettings {
        val reader = FieldReader(raw)
        return ThinkingBudgetsSettings(
            minimal = reader.long("minimal"),
            low = reader.long("low"),
            medium = reader.long("medium"),
            high = reader.long("high"),
        )
    }

    private fun decodeMarkdown(raw: JsonObject): MarkdownSettings {
        val reader = FieldReader(raw)
        return MarkdownSettings(
            codeBlockIndent = reader.string("codeBlockIndent") ?: SettingsDefaults.MARKDOWN_CODE_BLOCK_INDENT,
            mermaid = reader.string("mermaid") ?: SettingsDefaults.MARKDOWN_MERMAID,
        )
    }

    private fun decodeWarnings(raw: JsonObject): WarningSettings {
        val reader = FieldReader(raw)
        return WarningSettings(
            anthropicExtraUsage = reader.boolean("anthropicExtraUsage") ?: SettingsDefaults.WARNINGS_ANTHROPIC_EXTRA_USAGE,
        )
    }

    private fun decodeCodemode(raw: JsonObject): CodemodeSettings {
        val reader = FieldReader(raw)
        return CodemodeSettings(
            mode = reader.string("mode") ?: SettingsDefaults.CODEMODE_MODE,
            inlineBudget = reader.long("inlineBudget") ?: SettingsDefaults.CODEMODE_INLINE_BUDGET,
        )
    }

    private class FieldReader(private val raw: JsonObject) {
        fun string(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun boolean(key: String): Boolean? {
            val primitive = raw[key] as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            return when (primitive.content) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        fun long(key: String): Long? {
            val primitive = raw[key] as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            primitive.content.toLongOrNull()?.let { return it }
            val number = primitive.content.toDoubleOrNull() ?: return null
            return if (number.isFinite() && number % 1.0 == 0.0) number.toLong() else null
        }

        fun obj(key: String): JsonObject? = raw[key] as? JsonObject

        fun arr(key: String): JsonArray? = raw[key] as? JsonArray

        fun strings(key: String): List<String>? = arr(key)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        fun stringMap(key: String): Map<String, String>? = obj(key)?.let { map ->
            map.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
            }.toMap()
        }

        fun element(key: String): JsonElement? = raw[key]
    }
}
