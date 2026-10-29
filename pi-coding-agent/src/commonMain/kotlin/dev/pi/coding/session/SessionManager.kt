package dev.pi.coding.session

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.JsonValue
import dev.pi.ai.Message
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.utils.getCurrentSystemMessage
import dev.pi.coding.paths.AgentPaths
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

data class SessionContext(
    val messages: List<Message>,
    val provider: String?,
    val modelId: String?,
    val thinkingLevel: String?,
)

data class SessionInfo(
    val sessionId: String,
    val sessionFile: Path,
    val cwd: String,
    val timestamp: String,
    val name: String?,
    val messageCount: Int,
)

data class SessionStats(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalCost: Double,
)

data class TreeNode(
    val entry: SessionEntry,
    val children: List<TreeNode>,
)

class SessionManager private constructor(
    private val fs: FileSystem,
    private val sessionDirPath: Path?,
    private val persist: Boolean,
    private var cwdValue: Path,
) {
    private var sessionIdValue: String = ""
    private var sessionFileValue: Path? = null
    private var headerValue: SessionHeader = SessionHeader(id = "", timestamp = "", cwd = "")
    private val entries: MutableList<SessionEntry> = mutableListOf()
    private val byId: MutableMap<String, SessionEntry> = LinkedHashMap()
    private val labelsById: MutableMap<String, String> = LinkedHashMap()
    private val labelTimestampsById: MutableMap<String, String> = LinkedHashMap()
    private var leafIdValue: String? = null
    private var flushed: Boolean = false
    private var appendTailVerified: Boolean = false
    private var lastUuidV7Timestamp: Long = -1L
    private var uuidV7Sequence: Long? = null

    val sessionId: String get() = sessionIdValue

    val cwd: Path get() = cwdValue

    val sessionFile: Path? get() = sessionFileValue

    fun getHeader(): SessionHeader = headerValue

    fun getEntries(): List<SessionEntry> = entries.toList()

    fun getEntry(id: String): SessionEntry? = byId[id]

    fun getLeafId(): String? = leafIdValue

    fun getBranch(fromId: String? = null): List<SessionEntry> {
        val startId = fromId ?: leafIdValue ?: return emptyList()
        var current = byId[startId] ?: return emptyList()
        val path = mutableListOf<SessionEntry>()
        while (true) {
            path.add(current)
            val parentId = current.parentId ?: break
            current = byId[parentId] ?: break
        }
        path.reverse()
        return path
    }

    fun getSessionName(): String? {
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            if (entry is SessionInfoEntry) return entry.name?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun getTree(): List<TreeNode> {
        val nodeByEntry: MutableMap<String, MutableNode> = LinkedHashMap()
        for (entry in entries) {
            nodeByEntry[entry.id] = MutableNode(entry)
        }
        val roots = mutableListOf<MutableNode>()
        for (entry in entries) {
            val node = nodeByEntry[entry.id]!!
            val parentId = entry.parentId
            if (parentId == null || parentId == entry.id || !nodeByEntry.containsKey(parentId)) {
                roots.add(node)
            } else {
                nodeByEntry[parentId]!!.children.add(node)
            }
        }
        val timestamps = HashMap<String, Long>()
        fun timestampOf(entry: SessionEntry): Long = timestamps.getOrPut(entry.id) {
            isoToEpochMillis(entry.timestamp) ?: 0L
        }
        val built = HashMap<MutableNode, TreeNode>()
        val stack = ArrayDeque<Pair<MutableNode, Boolean>>()
        for (root in roots) stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (node, expanded) = stack.removeLast()
            if (!expanded) {
                stack.addLast(node to true)
                node.children.sortBy { timestampOf(it.entry) }
                for (child in node.children) stack.addLast(child to false)
            } else {
                built[node] = TreeNode(node.entry, node.children.map { built[it]!! })
            }
        }
        return roots.map { built[it]!! }
    }

    fun buildContextEntries(): List<SessionEntry> = buildContextEntriesFor(leafIdValue)

    fun buildSessionProjection(): List<SessionEntry> {
        val contextEntries = buildContextEntriesFor(leafIdValue)
        val edits = collectContextEdits(contextEntries)
        val projected = mutableListOf<SessionEntry>()
        for (entry in contextEntries) {
            val edit = edits[entry.id]
            if (edit == null) {
                projected.add(entry)
                continue
            }
            val replacement = edit.replacement ?: continue
            projected.add(projectEntryContent(entry, replacement.content))
        }
        return projected
    }

    fun buildSessionContext(): SessionContext {
        val path = getBranch(leafIdValue)
        val settings = resolveContextSettings(path)
        val contextEntries = buildContextEntriesFor(leafIdValue)
        val edits = collectContextEdits(contextEntries)
        val messages = mutableListOf<Message>()
        for ((index, entry) in contextEntries.withIndex()) {
            if (entry is CompactionEntry && index > 0) continue
            val edit = edits[entry.id]
            if (edit != null && edit.replacement == null) continue
            val produced = contextMessagesFor(entry)
            if (edit == null) {
                messages.addAll(produced)
            } else {
                messages.addAll(applyContentReplacement(produced, edit.replacement!!.content))
            }
        }
        return SessionContext(
            messages = messages,
            provider = settings.provider,
            modelId = settings.modelId,
            thinkingLevel = settings.thinkingLevel,
        )
    }

    fun getSessionStats(): SessionStats {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var cost = 0.0
        fun addUsage(usage: Usage) {
            input += usage.input
            output += usage.output
            cacheRead += usage.cacheRead
            cacheWrite += usage.cacheWrite
            cost += usage.cost.total
        }
        for (entry in entries) {
            when (entry) {
                is UsageEntry -> addUsage(entry.usage)
                is CompactionEntry -> entry.usage?.let { addUsage(it) }
                is BranchSummaryEntry -> entry.usage?.let { addUsage(it) }
                is SessionMessageEntry -> when (val message = entry.message) {
                    is AssistantMessage -> addUsage(message.usage)
                    is ToolResultMessage -> message.usage?.let { addUsage(it) }
                    else -> Unit
                }

                else -> Unit
            }
        }
        return SessionStats(
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            totalCost = cost,
        )
    }

    suspend fun appendMessage(message: Message): SessionMessageEntry {
        val entry = SessionMessageEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            message = message,
            rawMessage = null,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendThinkingLevelChange(level: String): ThinkingLevelChangeEntry {
        val entry = ThinkingLevelChangeEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            thinkingLevel = level,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendModelChange(provider: String, modelId: String): ModelChangeEntry {
        val entry = ModelChangeEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            provider = provider,
            modelId = modelId,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendUsage(
        kind: String?,
        provider: String,
        model: String,
        usage: Usage,
        note: String? = null,
    ): UsageEntry {
        val entry = UsageEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            kind = kind,
            provider = provider,
            model = model,
            usage = usage,
            note = note,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendCompaction(
        summary: String,
        firstKeptEntryId: String,
        tokensBefore: Long,
        usage: Usage? = null,
        details: JsonValue? = null,
        systemMessage: SystemMessage? = null,
    ): CompactionEntry {
        val timestamp = nowIsoTimestamp()
        val snapshot = (systemMessage ?: getCurrentSystemMessage(buildSessionContext().messages))
            ?.copy(timestamp = isoToEpochMillis(timestamp) ?: 0L)
        val entry = CompactionEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = timestamp,
            summary = summary,
            firstKeptEntryId = firstKeptEntryId,
            tokensBefore = tokensBefore,
            usage = usage,
            details = details,
            systemMessage = snapshot,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendBranchSummary(
        fromId: String,
        summary: String,
        details: JsonValue? = null,
        usage: Usage? = null,
    ): BranchSummaryEntry {
        val entry = BranchSummaryEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            fromId = fromId,
            summary = summary,
            details = details,
            usage = usage,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendCustomEntry(customType: String, data: JsonValue? = null): CustomEntry {
        val entry = CustomEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            customType = customType,
            data = data,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendCustomMessageEntry(
        customType: String,
        content: dev.pi.ai.MessageContent,
        display: Boolean,
        details: JsonValue? = null,
    ): CustomMessageEntry {
        val entry = CustomMessageEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            customType = customType,
            content = content,
            display = display,
            details = details,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendContextEdit(
        targetId: String,
        replacement: ContentReplacement?,
    ): ContextEditEntry {
        val target = byId[targetId] ?: throw IllegalArgumentException("Entry $targetId not found")
        if (getBranch().none { it.id == targetId }) {
            throw IllegalArgumentException("Entry $targetId is not on the active branch")
        }
        val editable = target is CustomMessageEntry ||
            (target is SessionMessageEntry && (target.message is UserMessage ||
                target.message is AssistantMessage ||
                target.message is ToolResultMessage))
        if (!editable) throw IllegalArgumentException("Entry $targetId does not contribute editable model content")
        val targetRole = when {
            target is SessionMessageEntry -> when (target.message) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                is ToolResultMessage -> "toolResult"
                else -> "user"
            }

            else -> "custom"
        }
        val normalized = normalizeReplacement(targetRole, replacement)
        val entry = ContextEditEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            targetId = targetId,
            replacement = normalized,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun appendLabelChange(targetId: String, label: String?): LabelEntry {
        if (!byId.containsKey(targetId)) throw IllegalArgumentException("Entry $targetId not found")
        val entry = LabelEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            targetId = targetId,
            label = label,
        )
        appendEntry(entry)
        val effective = label?.takeIf { it.isNotEmpty() }
        if (effective != null) {
            labelsById[targetId] = effective
            labelTimestampsById[targetId] = entry.timestamp
        } else {
            labelsById.remove(targetId)
            labelTimestampsById.remove(targetId)
        }
        return entry
    }

    suspend fun appendSessionInfo(name: String?): SessionInfoEntry {
        val sanitized = name?.replace(NEWLINE_PATTERN, " ")?.trim()?.takeIf { it.isNotEmpty() }
        val entry = SessionInfoEntry(
            id = generateEntryId(),
            parentId = leafIdValue,
            timestamp = nowIsoTimestamp(),
            name = sanitized,
        )
        appendEntry(entry)
        return entry
    }

    fun branch(entryId: String) {
        if (!byId.containsKey(entryId)) throw IllegalArgumentException("Entry $entryId not found")
        leafIdValue = entryId
    }

    fun resetLeaf() {
        leafIdValue = null
    }

    suspend fun branchWithSummary(
        entryId: String,
        summary: String,
        details: JsonValue? = null,
        usage: Usage? = null,
    ): BranchSummaryEntry {
        if (!byId.containsKey(entryId)) throw IllegalArgumentException("Entry $entryId not found")
        val fromId = leafIdValue ?: ROOT_BRANCH_ID
        leafIdValue = entryId
        val entry = BranchSummaryEntry(
            id = generateEntryId(),
            parentId = entryId,
            timestamp = nowIsoTimestamp(),
            fromId = fromId,
            summary = summary,
            details = details,
            usage = usage,
        )
        appendEntry(entry)
        return entry
    }

    suspend fun createBranchedSession(leafId: String? = null): SessionManager {
        val resolvedLeafId = leafId ?: leafIdValue
        val path = if (resolvedLeafId == null) emptyList() else getBranch(resolvedLeafId)
        if (path.isEmpty()) throw IllegalArgumentException("Entry ${resolvedLeafId ?: ""} not found")
        val pathWithoutLabels = mutableListOf<SessionEntry>()
        val replacementByLabelId = mutableMapOf<String, String>()
        val pendingLabelIds = mutableListOf<String>()
        var parentId: String? = null
        for (entry in path) {
            if (entry is LabelEntry) {
                pendingLabelIds.add(entry.id)
                continue
            }
            for (labelId in pendingLabelIds) replacementByLabelId[labelId] = entry.id
            pendingLabelIds.clear()
            val reanchored = if (entry is CompactionEntry) {
                entry.copy(
                    parentId = parentId,
                    firstKeptEntryId = if (entry.firstKeptEntryId == entry.id) {
                        entry.id
                    } else {
                        replacementByLabelId[entry.firstKeptEntryId] ?: entry.firstKeptEntryId
                    },
                )
            } else {
                entry.withParentId(parentId)
            }
            pathWithoutLabels.add(reanchored)
            parentId = entry.id
        }
        val newSessionId = createSessionId()
        val timestamp = nowIsoTimestamp()
        val newHeader = SessionHeader(
            type = "session",
            version = CURRENT_SESSION_VERSION,
            id = newSessionId,
            timestamp = timestamp,
            cwd = cwdValue.toString(),
            parentSession = if (persist) sessionFileValue?.toString() else null,
        )
        val pathEntryIds = pathWithoutLabels.mapTo(mutableSetOf()) { it.id }
        val labelsToWrite = labelsById.entries
            .filter { pathEntryIds.contains(it.key) }
            .map { LabelTarget(it.key, it.value, labelTimestampsById[it.key] ?: timestamp) }
        val labelEntries = mutableListOf<LabelEntry>()
        var labelParentId: String? = pathWithoutLabels.lastOrNull()?.id
        val usedIds = pathEntryIds
        for (labelTarget in labelsToWrite) {
            val labelEntry = LabelEntry(
                id = generateEntryIdExcluding(usedIds),
                parentId = labelParentId,
                timestamp = labelTarget.timestamp,
                targetId = labelTarget.targetId,
                label = labelTarget.label,
            )
            usedIds.add(labelEntry.id)
            labelEntries.add(labelEntry)
            labelParentId = labelEntry.id
        }
        val newEntries = pathWithoutLabels + labelEntries
        val newFile = if (persist && sessionDirPath != null) {
            sessionDirPath / sessionFileName(timestamp, newSessionId)
        } else {
            null
        }
        val manager = SessionManager(fs, sessionDirPath, persist, cwdValue)
        manager.initializeBranched(newHeader, newEntries, newFile)
        return manager
    }

    suspend fun close() {
    }

    private fun initializeNewSession(sessionId: String?, parentSession: String?) {
        val id = sessionId ?: createSessionId()
        if (sessionId != null) assertValidSessionId(id)
        val timestamp = nowIsoTimestamp()
        headerValue = SessionHeader(
            type = "session",
            version = CURRENT_SESSION_VERSION,
            id = id,
            timestamp = timestamp,
            cwd = cwdValue.toString(),
            parentSession = parentSession,
        )
        sessionIdValue = id
        sessionFileValue = if (persist && sessionDirPath != null) {
            sessionDirPath / sessionFileName(timestamp, id)
        } else {
            null
        }
        entries.clear()
        flushState()
        flushed = false
    }

    private fun initializeLoaded(loaded: LoadedSession, file: Path) {
        headerValue = loaded.header
        sessionIdValue = loaded.header.id
        sessionFileValue = file
        entries.clear()
        entries.addAll(loaded.entries)
        flushState()
        flushed = true
        if (loaded.migrated && persist) rewriteFile()
    }

    private fun initializeBranched(header: SessionHeader, newEntries: List<SessionEntry>, file: Path?) {
        headerValue = header
        sessionIdValue = header.id
        sessionFileValue = file
        entries.clear()
        entries.addAll(newEntries)
        flushState()
        flushed = false
        if (persist && file != null) {
            rewriteFile()
            flushed = true
        }
    }

    private fun flushState() {
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafIdValue = null
        for (entry in entries) {
            byId[entry.id] = entry
            leafIdValue = entry.id
            if (entry is LabelEntry) {
                val label = entry.label
                if (!label.isNullOrEmpty()) {
                    labelsById[entry.targetId] = label
                    labelTimestampsById[entry.targetId] = entry.timestamp
                } else {
                    labelsById.remove(entry.targetId)
                    labelTimestampsById.remove(entry.targetId)
                }
            }
        }
    }

    private fun appendEntry(entry: SessionEntry) {
        entries.add(entry)
        byId[entry.id] = entry
        leafIdValue = entry.id
        persistEntry(entry)
    }

    private fun persistEntry(entry: SessionEntry) {
        if (!persist) return
        val file = sessionFileValue ?: return
        if (!flushed) {
            if (!hasConversation()) return
            rewriteFile()
            flushed = true
            return
        }
        if (!appendTailVerified) {
            ensureTrailingNewline(file)
            appendTailVerified = true
        }
        file.parent?.let { parent -> if (!fs.exists(parent)) fs.createDirectories(parent) }
        val sink = fs.appendingSink(file).buffer()
        try {
            sink.writeUtf8(encodeSessionEntry(entry))
            sink.writeUtf8("\n")
        } finally {
            sink.close()
        }
    }

    private fun ensureTrailingNewline(file: Path) {
        if (!fs.exists(file)) return
        val size = fs.metadataOrNull(file)?.size ?: return
        if (size == 0L) return
        val lastByte = fs.openReadOnly(file).use { handle ->
            val buffer = okio.Buffer()
            if (handle.read(size - 1, buffer, 1) != 1L) return
            buffer.readByte()
        }
        if (lastByte != NEWLINE_BYTE) {
            fs.appendingSink(file).buffer().use { sink -> sink.writeByte(NEWLINE_BYTE.toInt()) }
        }
    }

    private fun rewriteFile() {
        if (!persist) return
        val file = sessionFileValue ?: return
        file.parent?.let { parent -> if (!fs.exists(parent)) fs.createDirectories(parent) }
        fs.write(file) {
            writeUtf8(encodeSessionHeader(headerValue))
            writeUtf8("\n")
            for (entry in entries) {
                writeUtf8(encodeSessionEntry(entry))
                writeUtf8("\n")
            }
        }
        appendTailVerified = true
    }

    private fun hasConversation(): Boolean = entries.any { entry ->
        entry is SessionMessageEntry && entry.isConversationMessage()
    }

    private fun SessionMessageEntry.isConversationMessage(): Boolean {
        val role = rawMessage?.stringValue("role") ?: when (message) {
            is UserMessage -> "user"
            is AssistantMessage -> "assistant"
            else -> null
        }
        return role == "user" || role == "assistant"
    }

    private fun buildContextEntriesFor(leaf: String?): List<SessionEntry> {
        val path = getBranch(leaf)
        val compaction = path.lastOrNull { it is CompactionEntry } as CompactionEntry? ?: return path
        val compactionIndex = path.indexOfFirst { it.id == compaction.id }
        if (compactionIndex < 0) return path
        val contextEntries = mutableListOf<SessionEntry>(compaction)
        var foundFirstKept = false
        for (index in 0 until compactionIndex) {
            val entry = path[index]
            if (entry.id == compaction.firstKeptEntryId) foundFirstKept = true
            if (foundFirstKept && !(entry is SessionMessageEntry && entry.message is SystemMessage)) {
                contextEntries.add(entry)
            }
        }
        contextEntries.addAll(path.subList(compactionIndex + 1, path.size))
        return contextEntries
    }

    private fun collectContextEdits(contextEntries: List<SessionEntry>): Map<String, ContextEditEntry> {
        val edits = HashMap<String, ContextEditEntry>()
        for (entry in contextEntries) {
            if (entry is ContextEditEntry) edits[entry.targetId] = entry
        }
        return edits
    }

    private fun projectEntryContent(entry: SessionEntry, content: dev.pi.ai.MessageContent): SessionEntry = when (entry) {
        is SessionMessageEntry -> entry.copy(message = replaceMessageContent(entry.message, content))
        is CustomMessageEntry -> entry.copy(content = content)
        else -> entry
    }

    private fun replaceMessageContent(message: Message, content: dev.pi.ai.MessageContent): Message = when (message) {
        is UserMessage -> message.copy(content = content)
        is AssistantMessage -> message.copy(content = toContentList(content))
        is ToolResultMessage -> message.copy(content = toContentList(content))
        else -> message
    }

    private fun normalizeReplacement(targetRole: String, replacement: ContentReplacement?): ContentReplacement? {
        if (replacement == null) return null
        if ((targetRole == "assistant" || targetRole == "toolResult") && replacement.content is PlainTextContent) {
            val text = replacement.content.text
            return ContentReplacement(BlockContent(listOf(TextContent(text))))
        }
        return replacement
    }

    private fun contextMessagesFor(entry: SessionEntry): List<Message> = when (entry) {
        is SessionMessageEntry -> if (entry.isExcludedFromContext()) emptyList() else listOf(entry.message)
        is CustomMessageEntry -> listOf(
            UserMessage(
                content = toBlockContent(entry.content),
                timestamp = isoToEpochMillis(entry.timestamp) ?: 0L,
            ),
        )

        is BranchSummaryEntry -> if (entry.summary.isEmpty()) {
            emptyList()
        } else {
            listOf(
                UserMessage(
                    content = BlockContent(
                        listOf(TextContent(BRANCH_SUMMARY_PREFIX + entry.summary + BRANCH_SUMMARY_SUFFIX)),
                    ),
                    timestamp = isoToEpochMillis(entry.timestamp) ?: 0L,
                ),
            )
        }

        is CompactionEntry -> {
            val summaryMessage = UserMessage(
                content = BlockContent(
                    listOf(TextContent(COMPACTION_SUMMARY_PREFIX + entry.summary + COMPACTION_SUMMARY_SUFFIX)),
                ),
                timestamp = isoToEpochMillis(entry.timestamp) ?: 0L,
            )
            val systemMessage = entry.systemMessage
            if (systemMessage == null) listOf(summaryMessage) else listOf(systemMessage, summaryMessage)
        }

        else -> emptyList()
    }

    private fun applyContentReplacement(messages: List<Message>, content: dev.pi.ai.MessageContent): List<Message> =
        messages.map { message ->
            when (message) {
                is UserMessage -> message.copy(content = content)
                is AssistantMessage -> message.copy(content = toContentList(content))
                is ToolResultMessage -> message.copy(content = toContentList(content))
                else -> message
            }
        }

    private fun resolveContextSettings(path: List<SessionEntry>): ContextSettings {
        var thinkingLevel: String? = null
        var provider: String? = null
        var modelId: String? = null
        for (entry in path) {
            when (entry) {
                is ThinkingLevelChangeEntry -> thinkingLevel = entry.thinkingLevel
                is ModelChangeEntry -> {
                    provider = entry.provider
                    modelId = entry.modelId
                }

                is SessionMessageEntry -> if (entry.message is AssistantMessage) {
                    provider = entry.message.provider
                    modelId = entry.message.model
                }

                else -> Unit
            }
        }
        return ContextSettings(provider, modelId, thinkingLevel)
    }

    private fun generateEntryId(): String = generateEntryIdExcluding(byId.keys)

    private fun generateEntryIdExcluding(existing: Set<String>): String {
        repeat(ID_COLLISION_ATTEMPTS) {
            val candidate = randomHex(8)
            if (!existing.contains(candidate)) return candidate
        }
        return createSessionId()
    }

    private fun randomHex(length: Int): String {
        val builder = StringBuilder(length)
        repeat(length) { builder.append(HEX_DIGITS[random.nextInt(HEX_DIGITS.length)]) }
        return builder.toString()
    }

    private fun createSessionId(): String = uuidV7(null)

    private fun uuidV7(timestampMillis: Long?): String {
        val requested = timestampMillis ?: Clock.System.now().toEpochMilliseconds()
        require(requested in 0..MAX_UUID_V7_TIMESTAMP) {
            "UUIDv7 timestamp must be an integer between 0 and $MAX_UUID_V7_TIMESTAMP"
        }
        val effective = if (timestampMillis == null) {
            maxOf(requested, lastUuidV7Timestamp).also { lastUuidV7Timestamp = it }
        } else {
            requested
        }
        val bytes = ByteArray(16) { random.nextInt(256).toByte() }
        val currentSequence = uuidV7Sequence
        val sequence = if (currentSequence == null) {
            random.nextLong(0, MAX_UUID_V7_SEQUENCE + 1).also { uuidV7Sequence = it }
        } else {
            require(currentSequence < MAX_UUID_V7_SEQUENCE) { "UUIDv7 generator sequence exhausted" }
            (currentSequence + 1).also { uuidV7Sequence = it }
        }
        for (index in 5 downTo 0) {
            bytes[index] = ((effective ushr ((5 - index) * 8)) and 0xFF).toByte()
        }
        bytes[6] = (0x70 or ((sequence ushr 37) and 0x0F).toInt()).toByte()
        bytes[7] = ((sequence ushr 29) and 0xFF).toByte()
        bytes[8] = (0x80 or ((sequence ushr 23) and 0x3F).toInt()).toByte()
        bytes[9] = ((sequence ushr 15) and 0xFF).toByte()
        bytes[10] = ((sequence ushr 7) and 0xFF).toByte()
        bytes[11] = ((((sequence and 0x7F) shl 1) or (bytes[11].toLong() and 0x01)).toInt()).toByte()
        val hex = buildString(32) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun nowIsoTimestamp(): String = isoTimestamp(Clock.System.now().toEpochMilliseconds())

    private class MutableNode(val entry: SessionEntry) {
        val children: MutableList<MutableNode> = mutableListOf()
    }

    private class LabelTarget(val targetId: String, val label: String, val timestamp: String)

    private class ContextSettings(val provider: String?, val modelId: String?, val thinkingLevel: String?)

    private class LoadedSession(
        val header: SessionHeader,
        val entries: List<SessionEntry>,
        val migrated: Boolean,
    )

    companion object {
        suspend fun create(paths: AgentPaths, fs: FileSystem, sessionId: String? = null): SessionManager {
            val manager = SessionManager(fs, paths.sessionsForCwd(), true, paths.cwd.normalized())
            manager.initializeNewSession(sessionId, null)
            return manager
        }

        suspend fun open(file: Path, fs: FileSystem): SessionManager {
            val resolved = file.normalized()
            val loaded = loadSession(resolved, fs)
            if (loaded != null) {
                val sessionCwd = loaded.header.cwd.takeIf { it.isNotEmpty() }?.toPath()
                    ?: resolved.parent
                    ?: resolved
                val manager = SessionManager(fs, resolved.parent ?: ".".toPath(), true, sessionCwd)
                manager.initializeLoaded(loaded, resolved)
                return manager
            }
            if (fs.exists(resolved)) {
                val size = fs.metadataOrNull(resolved)?.size ?: 0L
                if (size > 0L) {
                    throw IllegalArgumentException("Session file is not a valid pi session: $resolved")
                }
                val manager = SessionManager(fs, resolved.parent ?: ".".toPath(), true, resolved.parent ?: resolved)
                manager.initializeNewSession(null, null)
                manager.sessionFileValue = resolved
                manager.rewriteFile()
                manager.flushed = true
                return manager
            }
            val manager = SessionManager(fs, resolved.parent ?: ".".toPath(), true, resolved.parent ?: resolved)
            manager.initializeNewSession(null, null)
            manager.sessionFileValue = resolved
            return manager
        }

        suspend fun continueRecent(paths: AgentPaths, fs: FileSystem): SessionManager? {
            val dir = paths.sessionsForCwd()
            val files = jsonlFiles(dir, fs).sortedByDescending { lastModifiedMillis(it, fs) }
            for (file in files) {
                val header = readSessionHeader(file, fs) ?: continue
                if (!headerCwdMatches(header.cwd, paths.cwd)) continue
                return open(file, fs)
            }
            return null
        }

        fun inMemory(cwd: Path): SessionManager {
            val manager = SessionManager(FileSystem.SYSTEM, null, false, cwd.normalized())
            manager.initializeNewSession(null, null)
            return manager
        }

        suspend fun list(paths: AgentPaths, fs: FileSystem): List<SessionInfo> {
            val dir = paths.sessionsForCwd()
            val infos = mutableListOf<Pair<SessionInfo, Long>>()
            for (file in jsonlFiles(dir, fs)) {
                val info = loadSessionInfo(file, fs) ?: continue
                infos.add(info to lastModifiedMillis(file, fs))
            }
            return infos.sortedByDescending { it.second }.map { it.first }
        }

        private fun loadSessionInfo(file: Path, fs: FileSystem): SessionInfo? {
            val objects = readSessionObjects(file, fs) ?: return null
            val header = objects.firstOrNull() ?: return null
            if (header.stringValue("type") != "session") return null
            val id = header.stringValue("id") ?: return null
            var name: String? = null
            var messageCount = 0
            for (index in 1 until objects.size) {
                val entry = objects[index]
                when (entry.stringValue("type")) {
                    "session_info" -> name = entry.stringValue("name")?.trim()?.takeIf { it.isNotEmpty() }
                    "message" -> messageCount += 1
                    else -> Unit
                }
            }
            return SessionInfo(
                sessionId = id,
                sessionFile = file,
                cwd = header.stringValue("cwd") ?: "",
                timestamp = header.stringValue("timestamp") ?: "",
                name = name,
                messageCount = messageCount,
            )
        }

        private fun readSessionObjects(file: Path, fs: FileSystem): List<JsonObject>? {
            if (!fs.exists(file)) return null
            val text = try {
                fs.read(file) { readUtf8() }
            } catch (_: Exception) {
                return null
            }
            return parseSessionObjects(text)
        }

        private fun parseSessionObjects(text: String): List<JsonObject>? {
            if (text.isBlank()) return null
            val objects = mutableListOf<JsonObject>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val element = runCatching { sessionJson.parseToJsonElement(trimmed) }.getOrNull()
                if (element is JsonObject) objects.add(element)
            }
            return objects
        }

        suspend fun findById(sessionId: String, paths: AgentPaths, fs: FileSystem): SessionManager? {
            val dir = paths.sessionsForCwd()
            for (file in jsonlFiles(dir, fs)) {
                val header = readSessionHeader(file, fs) ?: continue
                if (header.id == sessionId) return open(file, fs)
            }
            return null
        }

        private fun jsonlFiles(dir: Path, fs: FileSystem): List<Path> =
            (fs.listOrNull(dir) ?: emptyList()).filter { it.name.endsWith(".jsonl") }

        private fun lastModifiedMillis(file: Path, fs: FileSystem): Long =
            fs.metadataOrNull(file)?.lastModifiedAtMillis ?: 0L

        private fun headerCwdMatches(headerCwd: String, expected: Path): Boolean {
            val trimmed = headerCwd.trim()
            if (trimmed.isEmpty()) return false
            val headerPath = trimmed.toPathOrNull() ?: return false
            return headerPath.normalized() == expected.normalized()
        }

        private fun loadSession(file: Path, fs: FileSystem): LoadedSession? {
            val bytes = try {
                fs.read(file) { readByteArray() }
            } catch (_: Exception) {
                return null
            }
            val repaired = repairSessionTail(file, fs, bytes)
            val objects = parseSessionObjects(repaired.decodeToString())?.toMutableList() ?: return null
            if (objects.isEmpty()) return null
            val first = objects.first()
            if (first.stringValue("type") != "session") return null
            if (first.stringValue("id") == null) return null
            val migrated = migrateSessionObjects(objects)
            val header = runCatching { decodeSessionHeader(objects.first()) }.getOrNull() ?: return null
            val decodedEntries = objects.drop(1).mapNotNull { decodeSessionEntry(it) }
            return LoadedSession(header, decodedEntries, migrated)
        }

        private fun repairSessionTail(file: Path, fs: FileSystem, bytes: ByteArray): ByteArray {
            if (bytes.isEmpty()) return bytes
            var lineStart = 0
            var candidateEnd = 0
            var tailBroken = false
            var hasNonBlank = false
            var lastNonBlankValid = true
            while (true) {
                var newlineIndex = -1
                var index = lineStart
                while (index < bytes.size) {
                    if (bytes[index] == NEWLINE_BYTE) {
                        newlineIndex = index
                        break
                    }
                    index++
                }
                val lineEnd = if (newlineIndex >= 0) newlineIndex else bytes.size
                val lineEndWithNewline = if (newlineIndex >= 0) newlineIndex + 1 else bytes.size
                if (isBlankLine(bytes, lineStart, lineEnd)) {
                    if (!tailBroken) candidateEnd = lineEndWithNewline
                } else {
                    hasNonBlank = true
                    val valid = isJsonObjectLine(bytes, lineStart, lineEnd)
                    lastNonBlankValid = valid
                    if (valid) {
                        candidateEnd = lineEndWithNewline
                        tailBroken = false
                    } else {
                        tailBroken = true
                    }
                }
                if (newlineIndex < 0) break
                lineStart = newlineIndex + 1
            }
            if (!hasNonBlank) return bytes
            if (lastNonBlankValid) {
                if (bytes[bytes.size - 1] != NEWLINE_BYTE) {
                    fs.appendingSink(file).buffer().use { sink -> sink.writeByte(NEWLINE_BYTE.toInt()) }
                }
                return bytes
            }
            if (candidateEnd == 0) return bytes
            val corruptTail = bytes.copyOfRange(candidateEnd, bytes.size)
            writeCorruptBackup(file, fs, corruptTail)
            val repaired = bytes.copyOf(candidateEnd)
            fs.write(file) { write(repaired) }
            return repaired
        }

        private fun writeCorruptBackup(file: Path, fs: FileSystem, corruptTail: ByteArray) {
            val parent = file.parent ?: return
            val backup = parent / (file.name + CORRUPT_FILE_SUFFIX)
            if (!fs.exists(parent)) fs.createDirectories(parent)
            val backupSize = if (fs.exists(backup)) fs.metadataOrNull(backup)?.size ?: 0L else 0L
            fs.appendingSink(backup).buffer().use { sink ->
                if (backupSize > 0L) sink.writeUtf8("\n")
                val timestamp = isoTimestamp(Clock.System.now().toEpochMilliseconds())
                sink.writeUtf8(
                    "===== corrupt session tail from ${file} at $timestamp (${corruptTail.size} bytes) =====\n",
                )
                sink.write(corruptTail)
                if (corruptTail.isEmpty() || corruptTail[corruptTail.size - 1] != NEWLINE_BYTE) {
                    sink.writeByte(NEWLINE_BYTE.toInt())
                }
            }
        }

        private fun isBlankLine(bytes: ByteArray, start: Int, end: Int): Boolean {
            for (index in start until end) {
                val byte = bytes[index].toInt()
                if (byte != SPACE_BYTE && byte != TAB_BYTE && byte != CARRIAGE_RETURN_BYTE) return false
            }
            return true
        }

        private fun isJsonObjectLine(bytes: ByteArray, start: Int, end: Int): Boolean {
            if (end <= start) return false
            val text = bytes.copyOfRange(start, end).decodeToString().trim()
            if (text.isEmpty()) return false
            val element = runCatching { sessionJson.parseToJsonElement(text) }.getOrNull()
            return element is JsonObject
        }

        private fun readSessionHeader(file: Path, fs: FileSystem): SessionHeader? {
            val source = try {
                fs.source(file).buffer()
            } catch (_: Exception) {
                return null
            }
            try {
                val builder = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: return null
                    if (builder.length + line.length > MAX_SESSION_HEADER_SCAN_BYTES) return null
                    builder.append(line).append('\n')
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val element = runCatching { sessionJson.parseToJsonElement(trimmed) }.getOrNull()
                    if (element !is JsonObject) continue
                    if (element.stringValue("type") != "session") return null
                    if (element.stringValue("id") == null) return null
                    return runCatching { decodeSessionHeader(element) }.getOrNull()
                }
            } finally {
                source.close()
            }
        }

        private fun migrateSessionObjects(objects: MutableList<JsonObject>): Boolean {
            val headerIndex = objects.indexOfFirst { it.stringValue("type") == "session" }
            if (headerIndex < 0) return false
            val version = objects[headerIndex].intValue("version") ?: 1
            if (version >= CURRENT_SESSION_VERSION) return false
            if (version < 2) {
                val usedIds = mutableSetOf<String>()
                var previousId: JsonElement = JsonNull
                for (index in objects.indices) {
                    val obj = objects[index]
                    if (obj.stringValue("type") == "session") {
                        objects[index] = JsonObject(obj.toMutableMap().apply { put("version", JsonPrimitive(2)) })
                        continue
                    }
                    val id = generateMigrationId(usedIds)
                    usedIds.add(id)
                    val updated = obj.toMutableMap()
                    updated["id"] = JsonPrimitive(id)
                    updated["parentId"] = previousId
                    previousId = JsonPrimitive(id)
                    if (obj.stringValue("type") == "compaction") {
                        val firstKeptIndex = obj.intValue("firstKeptEntryIndex")
                        if (firstKeptIndex != null) {
                            val target = objects.getOrNull(firstKeptIndex)
                            val targetId = target?.stringValue("id")
                            if (target != null && target.stringValue("type") != "session" && targetId != null) {
                                updated["firstKeptEntryId"] = JsonPrimitive(targetId)
                            }
                            updated.remove("firstKeptEntryIndex")
                        }
                    }
                    objects[index] = JsonObject(updated)
                }
            }
            val migratedHeaderIndex = objects.indexOfFirst { it.stringValue("type") == "session" }
            objects[migratedHeaderIndex] =
                JsonObject(objects[migratedHeaderIndex].toMutableMap().apply {
                    put("version", JsonPrimitive(CURRENT_SESSION_VERSION))
                })
            if (version < 3) {
                for (index in objects.indices) {
                    val obj = objects[index]
                    if (obj.stringValue("type") != "message") continue
                    val message = obj["message"] as? JsonObject ?: continue
                    if (message.stringValue("role") != "hookMessage") continue
                    val updatedMessage = JsonObject(message.toMutableMap().apply { put("role", JsonPrimitive("custom")) })
                    objects[index] = JsonObject(obj.toMutableMap().apply { put("message", updatedMessage) })
                }
            }
            return true
        }

        private fun generateMigrationId(used: Set<String>): String {
            repeat(ID_COLLISION_ATTEMPTS) {
                val builder = StringBuilder(8)
                repeat(8) { builder.append(HEX_DIGITS[random.nextInt(HEX_DIGITS.length)]) }
                val candidate = builder.toString()
                if (!used.contains(candidate)) return candidate
            }
            return uuidV7String()
        }

        private fun uuidV7String(): String {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val randomValue = Random.nextLong()
            val hex = StringBuilder(32)
            for (index in 5 downTo 0) {
                val value = ((timestamp ushr ((5 - index) * 8)) and 0xFF).toInt()
                hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
            }
            val tail = randomValue and 0x7FFFFFFFFFFFFFFFL
            repeat(20) { position ->
                val shift = (19 - position) * 4
                val nibble = ((tail ushr shift) and 0x0F).toInt()
                hex.append(HEX_DIGITS[nibble])
            }
            val joined = hex.toString()
            return "${joined.substring(0, 8)}-${joined.substring(8, 12)}-${joined.substring(12, 16)}-" +
                "${joined.substring(16, 20)}-${joined.substring(20)}"
        }

        internal fun assertValidSessionId(id: String) {
            if (!SESSION_ID_PATTERN.matches(id)) {
                throw IllegalArgumentException(
                    "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
                        "and start and end with an alphanumeric character",
                )
            }
        }
    }

}

private fun sessionFileName(timestamp: String, sessionId: String): String =
    "${timestamp.replace(':', '-').replace('.', '-')}_$sessionId.jsonl"

private fun SessionEntry.withParentId(parentId: String?): SessionEntry = when (this) {
    is SessionMessageEntry -> copy(parentId = parentId)
    is ThinkingLevelChangeEntry -> copy(parentId = parentId)
    is ModelChangeEntry -> copy(parentId = parentId)
    is UsageEntry -> copy(parentId = parentId)
    is CompactionEntry -> copy(parentId = parentId)
    is BranchSummaryEntry -> copy(parentId = parentId)
    is CustomEntry -> copy(parentId = parentId)
    is CustomMessageEntry -> copy(parentId = parentId)
    is ContextEditEntry -> copy(parentId = parentId)
    is LabelEntry -> copy(parentId = parentId)
    is SessionInfoEntry -> copy(parentId = parentId)
}

private fun String.toPathOrNull(): Path? = try {
    toPath()
} catch (_: Exception) {
    null
}

private fun isoTimestamp(epochMillis: Long): String {
    val days = floorDiv(epochMillis, 86_400_000L)
    val millisOfDay = floorMod(epochMillis, 86_400_000L)
    val civil = civilFromDays(days)
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay / 60_000L) % 60).toInt()
    val second = ((millisOfDay / 1_000L) % 60).toInt()
    val millis = (millisOfDay % 1_000L).toInt()
    return buildString(24) {
        appendPadded(civil.year, 4)
        append('-')
        appendPadded(civil.month, 2)
        append('-')
        appendPadded(civil.day, 2)
        append('T')
        appendPadded(hour, 2)
        append(':')
        appendPadded(minute, 2)
        append(':')
        appendPadded(second, 2)
        append('.')
        appendPadded(millis, 3)
        append('Z')
    }
}

private fun isoToEpochMillis(value: String): Long? {
    if (value.length < 19) return null
    if (value[4] != '-' || value[7] != '-' || value[13] != ':' || value[16] != ':') return null
    if (value[10] != 'T' && value[10] != 't' && value[10] != ' ') return null
    val year = value.substring(0, 4).toIntOrNull() ?: return null
    val month = value.substring(5, 7).toIntOrNull() ?: return null
    val day = value.substring(8, 10).toIntOrNull() ?: return null
    val hour = value.substring(11, 13).toIntOrNull() ?: return null
    val minute = value.substring(14, 16).toIntOrNull() ?: return null
    val second = value.substring(17, 19).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    var position = 19
    var millis = 0L
    if (position < value.length && (value[position] == '.' || value[position] == ',')) {
        position += 1
        val fractionStart = position
        while (position < value.length && value[position].isDigit()) position += 1
        val fraction = value.substring(fractionStart, position)
        if (fraction.isEmpty()) return null
        millis = fraction.padEnd(3, '0').substring(0, 3).toLongOrNull() ?: return null
    }
    var offsetMinutes = 0L
    if (position < value.length) {
        when (val zone = value[position]) {
            'Z', 'z' -> position += 1
            '+', '-' -> {
                val compact = position + 5 <= value.length && value[position + 3] == ':'
                val offsetHourStart = position + 1
                val offsetHourEnd = offsetHourStart + 2
                if (offsetHourEnd > value.length) return null
                val offsetHour = value.substring(offsetHourStart, offsetHourEnd).toIntOrNull() ?: return null
                val offsetMinute = when {
                    compact && position + 6 <= value.length ->
                        value.substring(position + 4, position + 6).toIntOrNull() ?: return null
                    !compact && position + 5 <= value.length ->
                        value.substring(position + 3, position + 5).toIntOrNull() ?: return null
                    else -> 0
                }
                offsetMinutes = offsetHour * 60L + offsetMinute
                if (zone == '-') offsetMinutes = -offsetMinutes
                position += if (compact) 6 else 5
            }

            else -> return null
        }
    }
    if (position != value.length) return null
    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second
    return (seconds - offsetMinutes * 60L) * 1_000L + millis
}

private class CivilDate(val year: Int, val month: Int, val day: Int)

private fun civilFromDays(epochDays: Long): CivilDate {
    val z = epochDays + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year += 1L
    return CivilDate(year.toInt(), month.toInt(), day.toInt())
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var adjustedYear = year.toLong()
    if (month <= 2) adjustedYear -= 1
    val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399L) / 400L
    val yearOfEra = adjustedYear - era * 400L
    val monthPrime = if (month > 2) month - 3L else month + 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value xor divisor) < 0) quotient - 1 else quotient
}

private fun floorMod(value: Long, divisor: Long): Long = value - floorDiv(value, divisor) * divisor

private fun StringBuilder.appendPadded(value: Int, width: Int) {
    val text = value.toString()
    repeat((width - text.length).coerceAtLeast(0)) { append('0') }
    append(text)
}

private const val ROOT_BRANCH_ID = "root"

private const val CORRUPT_FILE_SUFFIX = ".corrupt"

private const val NEWLINE_BYTE = '\n'.code.toByte()

private const val CARRIAGE_RETURN_BYTE = '\r'.code

private const val SPACE_BYTE = ' '.code

private const val TAB_BYTE = '\t'.code

private const val ID_COLLISION_ATTEMPTS = 100

private const val MAX_SESSION_HEADER_SCAN_BYTES = 1024 * 1024

private const val MAX_UUID_V7_TIMESTAMP = 0xFFFFFFFFFFFFL

private const val MAX_UUID_V7_SEQUENCE = (1L shl 41) - 1L

private const val HEX_DIGITS = "0123456789abcdef"

private val NEWLINE_PATTERN = Regex("[\\r\\n]+")

private val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")

private val random = Random.Default
