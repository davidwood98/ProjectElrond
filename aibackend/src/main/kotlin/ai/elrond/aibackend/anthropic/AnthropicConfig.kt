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
         * Default model: Claude Haiku 4.5 — the fastest, most cost-effective tier, chosen
         * (2026-07-12) to keep the on-canvas /Q + extraction features cheap and snappy.
         * (History: originally claude-sonnet-4-20250514, deprecated → claude-sonnet-4-6.)
         * Override via [model] if needed.
         */
        const val DEFAULT_MODEL: String = "claude-haiku-4-5"
        const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION: String = "2023-06-01"
    }
}
