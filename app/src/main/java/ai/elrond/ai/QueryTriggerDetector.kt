package ai.elrond.ai

/**
 * Detects the handwritten AI trigger command at the end of the page content and
 * extracts the preceding handwriting as the prompt.
 *
 * The default trigger `/Q` is matched tolerantly (optional whitespace around the
 * slash, lowercase `q`, and a backslash misread of the slash). A custom trigger
 * (e.g. ">Q", "@Q") is matched as a case-insensitive literal at the end.
 */
object QueryTriggerDetector {

    const val DEFAULT_TRIGGER = "/Q"

    /**
     * How many of the best recognition candidates may carry the trigger before we stop
     * looking. Acts as a confidence floor: a `/Q` that only shows up in a low-ranked
     * (low-confidence) guess won't fire the assistant.
     */
    const val MAX_TRIGGER_CANDIDATES = 5

    private val DEFAULT_REGEX = Regex("""[/\\]\s*[qQ]\s*$""")

    private fun regexFor(trigger: String): Regex =
        if (trigger == DEFAULT_TRIGGER) {
            DEFAULT_REGEX
        } else {
            Regex(Regex.escape(trigger.trim()) + """\s*$""", RegexOption.IGNORE_CASE)
        }

    /** True when the text ends with the trigger (with or without a prompt before it). */
    fun containsTrigger(recognizedText: String, trigger: String = DEFAULT_TRIGGER): Boolean =
        regexFor(trigger).containsMatchIn(recognizedText.trim())

    /**
     * The text of the first candidate — within the top [maxCandidates], ranked best-first —
     * that ends with the trigger, or null when none do. Lets the trigger fire even if the
     * single best guess garbled it, while the rank cap rejects low-confidence false hits.
     */
    fun firstTriggerCandidate(
        candidates: List<String>,
        trigger: String = DEFAULT_TRIGGER,
        maxCandidates: Int = MAX_TRIGGER_CANDIDATES,
    ): String? =
        candidates.take(maxCandidates).firstOrNull { containsTrigger(it, trigger) }

    /**
     * @return the prompt text preceding a trailing trigger, or null when no
     *         trigger (or no prompt content) is present.
     */
    fun extractPrompt(recognizedText: String, trigger: String = DEFAULT_TRIGGER): String? {
        val trimmed = recognizedText.trim()
        val match = regexFor(trigger).find(trimmed) ?: return null
        val prompt = trimmed.removeRange(match.range).trim()
        return prompt.ifEmpty { null }
    }
}
