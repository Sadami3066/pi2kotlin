package dev.pi.coding.prompt

data class PromptTemplate(
    val name: String,
    val description: String?,
    val content: String,
    val argumentHint: String? = null,
    val filePath: String? = null,
)

fun parseCommandArgs(argsString: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var hasToken = false
    var inQuote: Char? = null
    for (char in argsString) {
        val quote = inQuote
        when {
            quote != null -> {
                if (char == quote) {
                    inQuote = null
                } else {
                    current.append(char)
                }
            }

            char == '"' || char == '\'' -> {
                inQuote = char
                hasToken = true
            }

            char.isWhitespace() -> {
                if (hasToken) {
                    args.add(current.toString())
                    current.clear()
                    hasToken = false
                }
            }

            else -> {
                current.append(char)
                hasToken = true
            }
        }
    }
    if (hasToken) args.add(current.toString())
    return args
}

private val ARGUMENT_PATTERN =
    Regex("""\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)""")

fun substituteArgs(content: String, args: List<String>): String {
    val allArgs = args.joinToString(" ")
    return ARGUMENT_PATTERN.replace(content) { match ->
        val groups = match.groupValues
        val defaultTarget = groups[1]
        val defaultValue = groups[2]
        val sliceStart = groups[3]
        val sliceLength = groups[4]
        val simple = groups[5]
        when {
            defaultTarget.isNotEmpty() -> {
                val value = if (defaultTarget == "@" || defaultTarget == "ARGUMENTS") {
                    allArgs
                } else {
                    args.getOrNull(defaultTarget.toInt() - 1)
                }
                if (!value.isNullOrEmpty()) value else defaultValue
            }

            sliceStart.isNotEmpty() -> {
                var start = sliceStart.toInt() - 1
                if (start < 0) start = 0
                if (sliceLength.isNotEmpty()) {
                    val length = sliceLength.toInt()
                    args.drop(start).take(length).joinToString(" ")
                } else {
                    args.drop(start).joinToString(" ")
                }
            }

            simple == "ARGUMENTS" || simple == "@" -> allArgs

            else -> args.getOrNull(simple.toInt() - 1) ?: ""
        }
    }
}

private val TEMPLATE_PATTERN = Regex("""^/([^\s]+)(?:\s+([\s\S]*))?$""")

fun expandPromptTemplate(text: String, templates: List<PromptTemplate>): String {
    if (!text.startsWith("/")) return text
    val match = TEMPLATE_PATTERN.find(text) ?: return text
    val templateName = match.groupValues[1]
    val argsString = match.groupValues[2]
    val template = templates.firstOrNull { it.name == templateName } ?: return text
    val args = parseCommandArgs(argsString)
    return substituteArgs(template.content, args)
}

class PromptTemplateExpander(
    private val templates: List<PromptTemplate> = emptyList(),
) {
    fun expand(text: String): String = expandPromptTemplate(text, templates)
}

internal fun parsePromptTemplateFile(rawContent: String, fileName: String, filePath: String?): PromptTemplate {
    val parsed = Frontmatter.parse(rawContent)
    val name = fileName.removeSuffix(".md")
    var description = parsed.frontmatter["description"].orEmpty()
    if (description.isEmpty()) {
        val firstLine = parsed.body.split("\n").firstOrNull { it.trim().isNotEmpty() }
        if (firstLine != null) {
            description = firstLine.take(60)
            if (firstLine.length > 60) description += "..."
        }
    }
    val argumentHint = parsed.frontmatter["argument-hint"]?.takeIf { it.isNotEmpty() }
    return PromptTemplate(
        name = name,
        description = description.takeIf { it.isNotEmpty() },
        content = parsed.body,
        argumentHint = argumentHint,
        filePath = filePath,
    )
}
