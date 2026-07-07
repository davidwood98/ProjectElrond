package ai.elrond.domain

/**
 * Pure description of the brush a tool draws with (FA-23) — the JVM-testable half of brush
 * derivation. The ink-native `Brush` is built from this at the UI boundary (`InkCanvas`), and
 * [familyKey] uses the same strings `StrokeSerialization` persists, so spec → brush → stored row
 * all agree on the family.
 */
data class BrushSpec(
    val familyKey: String,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
) {
    companion object {
        const val FAMILY_PRESSURE_PEN = "pressure-pen"
        const val FAMILY_HIGHLIGHTER = "highlighter"
        const val FAMILY_PENCIL = "pencil"
    }
}
