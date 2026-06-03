package ai.elrond.ai

/**
 * Detects the handwritten AI trigger command — `/Q` written at the end of the
 * page content — and extracts the preceding handwriting as the prompt.
 *
 * Tolerant of common recognition quirks: optional whitespace around the slash,
 * lowercase `q`, and a backslash misread of the slash.
 */
object QueryTriggerDetector {

    private val TRIGGER_AT_END = Regex("""[/\\]\s*[qQ]\s*$""")

    /** True when the text ends with the /Q trigger (with or without a prompt before it). */
    fun containsTrigger(recognizedText: String): Boolean =
        TRIGGER_AT_END.containsMatchIn(recognizedText.trim())

    /**
     * @return the prompt text preceding a trailing `/Q`, or null when no
     *         trigger (or no prompt content) is present.
     */
    fun extractPrompt(recognizedText: String): String? {
        val trimmed = recognizedText.trim()
        val match = TRIGGER_AT_END.find(trimmed) ?: return null
        val prompt = trimmed.removeRange(match.range).trim()
        return prompt.ifEmpty { null }
    }
}
