package dev.pi.coding.compaction

object SummaryPrompts {
    const val SUMMARIZATION_SYSTEM_PROMPT: String = "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.\n\nDo NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."

    const val SUMMARIZATION_PROMPT: String = "The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.\n\nUse this EXACT format:\n\n## Goal\n[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]\n\n## Constraints & Preferences\n- [Any constraints, preferences, or requirements mentioned by user]\n- [Or \"(none)\" if none were mentioned]\n\n## Progress\n### Done\n- [x] [Completed tasks/changes]\n\n### In Progress\n- [ ] [Current work]\n\n### Blocked\n- [Issues preventing progress, if any]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale]\n\n## Next Steps\n1. [Ordered list of what should happen next]\n\n## Critical Context\n- [Any data, examples, or references needed to continue]\n- [Or \"(none)\" if not applicable]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

    const val UPDATE_SUMMARIZATION_INSTRUCTIONS: String = "Update the existing structured summary with new information. RULES:\n- PRESERVE all existing information from the previous summary\n- ADD new progress, decisions, and context from the new messages\n- UPDATE the Progress section: move items from \"In Progress\" to \"Done\" when completed\n- UPDATE \"Next Steps\" based on what was accomplished\n- PRESERVE exact file paths, function names, and error messages\n- If something is no longer relevant, you may remove it\n\nUse this EXACT format:\n\n## Goal\n[Preserve existing goals, add new ones if the task expanded]\n\n## Constraints & Preferences\n- [Preserve existing, add new ones discovered]\n\n## Progress\n### Done\n- [x] [Include previously done items AND newly completed items]\n\n### In Progress\n- [ ] [Current work - update based on progress]\n\n### Blocked\n- [Current blockers - remove if resolved]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale] (preserve all previous, add new)\n\n## Next Steps\n1. [Update based on current state]\n\n## Critical Context\n- [Preserve important context, add new if needed]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

    const val TURN_PREFIX_SUMMARIZATION_PROMPT: String = "The messages above are earlier context from an ongoing conversation. Later messages are stored separately and do not need to be reconstructed.\n\nCreate a concise checkpoint of the user's request and the progress shown above. This checkpoint will be placed before the later messages so the conversation can continue with the necessary context.\n\n## Original Request\n[What did the user ask for?]\n\n## Progress So Far\n- [Key decisions and work completed in these messages]\n\n## Context Needed to Continue\n- [Information from these messages needed to understand the later work]\n\nOnly summarize information explicitly present above. Do not infer or recreate later messages."

    const val BRANCH_SUMMARY_PREAMBLE: String = "The user explored a different conversation branch before returning here.\nSummary of that exploration:\n\n"

    const val BRANCH_SUMMARY_PROMPT: String = "Create a structured summary of this conversation branch for context when returning later.\n\nUse this EXACT format:\n\n## Goal\n[What was the user trying to accomplish in this branch?]\n\n## Constraints & Preferences\n- [Any constraints, preferences, or requirements mentioned]\n- [Or \"(none)\" if none were mentioned]\n\n## Progress\n### Done\n- [x] [Completed tasks/changes]\n\n### In Progress\n- [ ] [Work that was started but not finished]\n\n### Blocked\n- [Issues preventing progress, if any]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale]\n\n## Next Steps\n1. [What should happen next to continue this work]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

    const val COMPACTION_SUMMARY_PREFIX: String = "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"

    const val COMPACTION_SUMMARY_SUFFIX: String = "\n</summary>"

    const val BRANCH_SUMMARY_PREFIX: String = "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"

    const val BRANCH_SUMMARY_SUFFIX: String = "</summary>"

    const val UPDATE_SUMMARIZATION_PROMPT: String =
        "The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.\n\n" +
            UPDATE_SUMMARIZATION_INSTRUCTIONS

    const val SPLIT_TURN_MARKER: String = "**Turn Context (split turn):**"

    const val CONVERSATION_OPEN: String = "<conversation>\n"
    const val CONVERSATION_CLOSE: String = "\n</conversation>\n\n"
    const val PREVIOUS_SUMMARY_OPEN: String = "<previous-summary>\n"
    const val PREVIOUS_SUMMARY_CLOSE: String = "\n</previous-summary>\n\n"
    const val ADDITIONAL_FOCUS_PREFIX: String = "\n\nAdditional focus: "
    const val TURN_PREFIX_INPUT_PREFIX: String = "# Conversation\n"
    const val TURN_PREFIX_INSTRUCTIONS_PREFIX: String = "\n\n# Instructions\n"
    const val NO_PRIOR_HISTORY: String = "No prior history."
    const val SUMMARY_FAILED_SYSTEM_LABEL: String = "Summarization"
    const val SUMMARY_FAILED_TURN_PREFIX_LABEL: String = "Turn prefix summarization"
    const val SUMMARY_FAILED_BRANCH_LABEL: String = "Branch summarization"
}
