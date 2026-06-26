package ai.elrond.domain

/**
 * The unit system the AI assistant must use when an answer includes measurements. [METRIC]
 * (SI: metres, kilograms, °C) is the default; [IMPERIAL] uses feet/inches, pounds, °F. Drives a
 * directive in the `/Q` system prompt; it never makes the AI add units where none were asked. Kept
 * Compose/Android-free per the by-layer rule.
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL;

    companion object {
        val DEFAULT = METRIC

        fun fromName(name: String?): UnitSystem = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
