package ai.elrond.domain

/**
 * The subject colour-dot palette: a generated **full-spectrum pastel** set (~66 colours) referenced
 * by a stable integer [Subject.colorId]. Built as [HUE_COUNT] hues around the wheel × [SHADE_COUNT]
 * shade levels (descending lightness), so it spans the spectrum with shade depth per the spec.
 *
 * Pure data: colours are ARGB `Int`s (full alpha), so the domain stays Compose-free — the UI wraps
 * them in `androidx.compose.ui.graphics.Color(argb)`. [colorId]s are an index into [colors]; they are
 * stable as long as [HUE_COUNT]/[SHADE_COUNT] don't change, so they persist safely in the DB.
 */
object SubjectPalette {

    const val HUE_COUNT = 11
    const val SHADE_COUNT = 6
    const val SIZE = HUE_COUNT * SHADE_COUNT // 66

    /** Lightness per shade level (0..[SHADE_COUNT]-1): pastel at the top, deeper toward the bottom. */
    private val LIGHTNESS = floatArrayOf(0.86f, 0.80f, 0.73f, 0.66f, 0.59f, 0.52f)
    private const val SATURATION = 0.62f

    /** All palette colours as ARGB ints (full alpha), indexed by colorId (0 until [SIZE]). */
    val colors: List<Int> = buildList(SIZE) {
        for (hue in 0 until HUE_COUNT) {
            for (shade in 0 until SHADE_COUNT) {
                val h = hue * 360f / HUE_COUNT
                add(hslToArgb(h, SATURATION, LIGHTNESS[shade]))
            }
        }
    }

    /** ARGB for a colorId; out-of-range ids clamp into the palette so a bad/legacy id never crashes. */
    fun argb(colorId: Int): Int = colors[normalize(colorId)]

    /** Clamps any int into a valid colorId (wraps negatives, mods overflow). */
    fun normalize(colorId: Int): Int = ((colorId % SIZE) + SIZE) % SIZE

    /** HSL → ARGB (alpha 0xFF). Pure, so the palette is fully unit-testable off-device. */
    private fun hslToArgb(hDeg: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = hDeg / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
