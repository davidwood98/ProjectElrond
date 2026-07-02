package ai.elrond.domain

/** Drawing tools available on the note canvas. */
enum class CanvasTool {
    PEN,

    /** Flat low-opacity emphasis marks (FA-23) — excluded from AI handwriting recognition. */
    HIGHLIGHTER,

    /** Graphite-textured writing tool (FA-23) — recognized like pen ink. */
    PENCIL,

    ERASER,

    /** Lasso selection (FA-9): circle ink to select, then move / scale / duplicate / … it. */
    LASSO,
}
