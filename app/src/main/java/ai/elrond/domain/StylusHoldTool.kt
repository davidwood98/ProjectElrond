package ai.elrond.domain

/**
 * The tool the S Pen button's press-and-hold gesture springs to while held (FA-19). Momentary: the
 * tool is active only while the button is down, reverting to the previous tool on release — so the
 * binding is a [CanvasTool] (or [NONE] = disabled), not the full [FingerGestureAction] list (Undo /
 * Redo / finger-draw don't have a sensible "while held" meaning). Compose-free, like the other
 * input enums; [toCanvasTool] is the bridge.
 */
enum class StylusHoldTool {
    NONE,
    PEN,
    ERASER,
    LASSO;

    /** The canvas tool to spring to while held, or null when the gesture is disabled. */
    fun toCanvasTool(): CanvasTool? = when (this) {
        NONE -> null
        PEN -> CanvasTool.PEN
        ERASER -> CanvasTool.ERASER
        LASSO -> CanvasTool.LASSO
    }

    companion object {
        val DEFAULT = ERASER
        fun fromName(name: String?): StylusHoldTool = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
