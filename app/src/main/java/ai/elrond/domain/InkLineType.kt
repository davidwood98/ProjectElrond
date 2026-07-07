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

    /**
     * The pattern as draw/gap dash intervals in page units for [brushSize], for preview rendering
     * with a dash path effect (round caps turn the near-zero "dot" intervals into dots). Empty for
     * [SOLID]. Derived from [patternRuns], so previews and baked segments always match.
     */
    fun dashIntervals(brushSize: Float): FloatArray {
        val runs = patternRuns
        if (runs.isEmpty()) return FloatArray(0)
        val out = FloatArray(runs.size * 2)
        runs.forEachIndexed { i, run ->
            out[i * 2] = (run.drawLen * brushSize).coerceAtLeast(DOT_INTERVAL_PX)
            out[i * 2 + 1] = run.gapLen * brushSize
        }
        return out
    }

    companion object {
        val DEFAULT = SOLID

        /** Near-zero "on" interval so a zero-length dot run still paints under a round cap. */
        private const val DOT_INTERVAL_PX = 0.1f

        fun fromName(name: String?): InkLineType = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** One on/off run of a line pattern, in multiples of the brush size. */
data class PatternRun(val drawLen: Float, val gapLen: Float)
