package ai.elrond.domain

/**
 * A canvas action a finger gesture (FA-19) can be bound to. Stays Compose-free in the domain layer
 * (like [AppAccent] / [ToolSelectedTreatment]); the human-readable labels live in the UI layer.
 *
 * [SELECT_HAND] toggles finger-draw mode (palm rejection off) rather than selecting a [CanvasTool] —
 * "finger draw" is not a tool, it's the stylus-only setting inverted.
 */
enum class FingerGestureAction {
    NONE,
    UNDO,
    REDO,
    LAST_TOOL_SWAP,
    SELECT_PEN,
    SELECT_ERASER,
    SELECT_LASSO,
    SELECT_HAND;

    companion object {
        fun fromName(name: String?): FingerGestureAction =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: NONE
    }
}
