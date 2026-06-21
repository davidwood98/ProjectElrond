package ai.elrond.domain

/** Drawing tools available on the note canvas. */
enum class CanvasTool {
    PEN,
    ERASER,

    /** Lasso selection (FA-9): circle ink to select, then move / scale / duplicate / … it. */
    LASSO,
}
