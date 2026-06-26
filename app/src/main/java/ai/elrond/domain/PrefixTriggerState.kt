package ai.elrond.domain

/**
 * State of the prefix `/Q` activation flow ([TriggerMode.PREFIX_COMMAND]): the user writes the
 * command on its own line first, the canvas shows a listening indicator, and a pause (inactivity)
 * ends the question. UI-only / in-memory — never persisted.
 */
sealed class PrefixTriggerState {

    /** Not listening: normal drawing. */
    object Idle : PrefixTriggerState()

    /**
     * The prefix command was detected; the canvas is listening for the question.
     *
     * @param triggerStrokeIds ids of the `/Q` strokes — removed on cancel.
     * @param promptStrokeIds  ids of the strokes written after the command (the question);
     *        grows as the user writes, and is removed alongside the trigger on cancel.
     */
    data class Listening(
        val triggerStrokeIds: List<String>,
        val promptStrokeIds: List<String>,
    ) : PrefixTriggerState()

    /** The inactivity timer fired: the question is being recognized and sent. */
    object Processing : PrefixTriggerState()
}
