package ai.elrond.domain

/**
 * Line style for pen and pencil strokes (FA-23). Non-[SOLID] strokes are baked into their
 * dash/dot segment strokes when they finish (see `LinePatterning`) — each segment carries the
 * tool's real brush (so a dashed pencil line keeps its texture) and the segments share a groupId
 * so the lasso treats the whole line as one object.
 */
enum class InkLineType {
    SOLID,

    /** Engineering centreline: long-short-long alternation. */
    CENTRELINE,
    DASHED,
    DOTTED,
    DASH_DOT;

    /**
     * The repeating on/off pattern, as (drawLen, gapLen) runs in multiples of the brush size.
     * A zero drawLen run is a single-point dot. Shared by stroke segmentation, the live preview,
     * the straighten preview, and the config-menu glyphs so they can never drift apart.
     */
    val patternRuns: List<PatternRun>
        get() = when (this) {
            SOLID -> emptyList()
            CENTRELINE -> listOf(PatternRun(12f, 3f), PatternRun(3f, 3f))
            DASHED -> listOf(PatternRun(6f, 3f))
            DOTTED -> listOf(PatternRun(0f, 2.5f))
            DASH_DOT -> listOf(PatternRun(8f, 3f), PatternRun(0f, 3f))
        }

    companion object {
        val DEFAULT = SOLID

        fun fromName(name: String?): InkLineType = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** One on/off run of a line pattern, in multiples of the brush size. */
data class PatternRun(val drawLen: Float, val gapLen: Float)
