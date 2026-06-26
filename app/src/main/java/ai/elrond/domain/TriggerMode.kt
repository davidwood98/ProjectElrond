package ai.elrond.domain

/** How the user activates the AI assistant on the canvas. Persisted in settings. */
enum class TriggerMode {
    /** Write the activation command (e.g. `/Q`) at the end of a handwriting line. */
    COMMAND,

    /**
     * Write the activation command (e.g. `/Q`) on its own line FIRST, then write the question;
     * a pause (inactivity) signals the end of the question and the query is sent.
     */
    PREFIX_COMMAND,

    /** Draw a lasso (a circle/loop) around the handwriting to ask about. */
    GESTURE;

    companion object {
        /** Parses a stored name, falling back to [COMMAND] for null/unknown values. */
        fun fromName(name: String?): TriggerMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: COMMAND
    }
}
