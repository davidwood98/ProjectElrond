package ai.elrond.aibackend

/**
 * Single source of truth for what the embedded `/Q` assistant can and cannot do.
 *
 * UPDATE THIS as the app gains abilities — it is injected into the system prompt
 * so the model knows to refuse out-of-scope requests with the standard failure
 * phrasing instead of hallucinating a capability it doesn't have.
 */
object AssistantCapabilities {

    val supported: List<String> = listOf(
        "Answer questions and explain concepts from general knowledge",
        "Do basic arithmetic and simple maths (fractions, simple algebra, " +
            "simple differentiation and integration without limits)",
        "Summarise, rephrase, or clarify the handwritten notes on the page",
        "Pull action items / tasks out of the notes for the to-do list",
    )

    val unsupported: List<String> = listOf(
        "Browse the web or look up live, real-time, or post-training information",
        "Open, change, or control app settings, files, or other apps",
        "Continue a conversation or remember earlier /Q questions (each /Q is one-shot)",
        "Create or edit calendar events (not available yet)",
    )

    /** System-prompt fragment describing scope and the required refusal behaviour. */
    fun systemPromptSection(): String = buildString {
        appendLine("You can:")
        supported.forEach { appendLine("- $it") }
        appendLine()
        appendLine("You cannot:")
        unsupported.forEach { appendLine("- $it") }
        appendLine()
        append(
            "If a request needs an ability you do not have, do not pretend or guess. " +
                "Reply with exactly: \"I could not complete that request because of \" " +
                "followed by a short reason (e.g. \"it needs live web access\").",
        )
    }
}
