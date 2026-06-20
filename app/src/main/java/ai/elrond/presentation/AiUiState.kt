package ai.elrond.presentation

/**
 * Transient state of the embedded AI assistant. Successful answers are not a
 * state here — they land on the canvas as [AiInkNote]s. [x]/[y] are canvas-pixel
 * positions used to place the on-canvas loading indicator and inline error.
 */
sealed interface AiUiState {
    data object Idle : AiUiState

    /** A trigger was detected and the prompt is in flight. */
    data class Thinking(val prompt: String, val x: Float = 0f, val y: Float = 0f) : AiUiState

    /** A failure/timeout; rendered as red handwriting near the trigger. */
    data class Error(val message: String, val x: Float = 0f, val y: Float = 0f) : AiUiState
}
