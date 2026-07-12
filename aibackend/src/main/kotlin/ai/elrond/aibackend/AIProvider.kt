package ai.elrond.aibackend

/**
 * Input to the AI assistant — either recognized text (from ML Kit handwriting
 * recognition) or a raw image crop of the handwritten region.
 */
sealed interface AIInput {
    data class Text(val text: String) : AIInput

    /**
     * @param base64Data Base64-encoded image bytes (no data-URI prefix).
     * @param prompt Optional text instruction accompanying the image
     *               (e.g. "Answer the question written in this image").
     */
    data class Image(
        val base64Data: String,
        val mediaType: String = "image/png",
        val prompt: String? = null,
    ) : AIInput
}

data class AIRequest(
    val input: AIInput,
    /** Stable system prompt — cached server-side via prompt caching when set. */
    val systemPrompt: String? = null,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS: Int = 1024
    }
}

data class AIResponse(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val stopReason: String?,
)

/** Failures from an [AIProvider], typed for caller-side handling. */
sealed class AIException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The API returned a non-success status (auth failure, billing fault, rate limit,
     * overload...). [errorType] is the provider's error-envelope `type` (e.g.
     * "authentication_error"), when it could be parsed; defaulted so existing constructions
     * keep compiling.
     */
    class Api(
        val statusCode: Int,
        message: String,
        val errorType: String? = null,
    ) : AIException(message) {
        /**
         * The account can't pay for the request (credits exhausted / billing problem) —
         * retrying won't help until the account is topped up. Anthropic reports this either
         * as a `billing_error` type or as an invalid_request_error whose message names the
         * credit balance, so both are matched.
         */
        val isBillingError: Boolean
            get() = errorType == "billing_error" ||
                message?.contains("credit balance", ignoreCase = true) == true

        /** The API key was missing/rejected — retrying won't help until the key is fixed. */
        val isAuthError: Boolean
            get() = statusCode == 401 || errorType == "authentication_error"
    }

    /** The request never completed (connectivity, timeout). */
    class Network(cause: Throwable) : AIException("Network error: ${cause.message}", cause)

    /** The API responded but the payload could not be parsed. */
    class Parse(message: String, cause: Throwable? = null) : AIException(message, cause)
}

/**
 * Abstraction over the underlying AI model so the implementation can be swapped
 * (Anthropic today, anything else tomorrow) without touching app code.
 */
interface AIProvider {
    suspend fun generate(request: AIRequest): Result<AIResponse>
}
