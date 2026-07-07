package ai.elrond.domain

/**
 * Pencil lead grades (FA-23 device feedback), light → dark. Each lead maps to a graphite colour:
 * harder leads (2H) are lighter and more translucent, softer leads (2B) darker and denser —
 * approximating real lead behaviour through colour + alpha on ink's textured pencil brush.
 * [HB] is the pre-selector pencil colour (the former `CanvasViewModel.PENCIL_COLOR`), so the
 * default is pixel-identical to every pencil stroke drawn before leads existed.
 */
enum class PencilLead(val label: String, val argb: Int) {
    TWO_H("2H", 0x735F666D),
    H("H", 0xA6515860.toInt()),
    HB("HB", 0xE043484E.toInt()),
    B("B", 0xF0343A40.toInt()),
    TWO_B("2B", 0xFF23272C.toInt());

    companion object {
        val DEFAULT = HB

        fun fromName(name: String?): PencilLead = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
