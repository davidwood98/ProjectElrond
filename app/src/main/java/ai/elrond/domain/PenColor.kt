package ai.elrond.domain

/**
 * Pen ink colours (FA-23). [BLUE] is the pre-FA-23 `USER_INK_COLOR` navy, so the default is
 * pixel-identical to every stroke drawn before colours existed.
 */
enum class PenColor(val argb: Int) {
    BLACK(0xFF212121.toInt()),
    RED(0xFFC62828.toInt()),
    BLUE(0xFF1A237E.toInt());

    companion object {
        val DEFAULT = BLUE

        fun fromName(name: String?): PenColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
