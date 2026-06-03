package ai.elrond.aibackend.anthropic

data class AnthropicConfig(
    /** Never hardcode — supply from local.properties / environment at the app layer. */
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        // Fail fast on transport-security misconfiguration.
        require(baseUrl.startsWith("https://")) { "baseUrl must use https" }
    }

    companion object {
        /**
         * Default model. Originally specified as claude-sonnet-4-20250514, but that
         * model is deprecated (retires 2026-06-15); claude-sonnet-4-6 is its
         * recommended drop-in replacement. Override via [model] if needed.
         */
        const val DEFAULT_MODEL: String = "claude-sonnet-4-6"
        const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION: String = "2023-06-01"
    }
}
