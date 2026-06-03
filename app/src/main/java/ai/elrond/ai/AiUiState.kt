package ai.elrond.ai

/**
 * Transient state of the embedded AI assistant. Successful responses are not a
 * state here — they land on the canvas as [AiInkNote]s.
 */
sealed interface AiUiState {
    data object Idle : AiUiState

    /** A /Q trigger was detected and the prompt is in flight. */
    data class Thinking(val prompt: String) : AiUiState

    data class Error(val message: String) : AiUiState
}
