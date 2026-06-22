package ai.elrond.ui.icons

import ai.elrond.R
import ai.elrond.domain.PenIconStyle
import androidx.annotation.DrawableRes

/**
 * Central registry of the bespoke note-tool / action icons from the Claude Design handoff
 * (`res/drawable/ic_*.xml`). One typed place to reference the set — and the single place to swap
 * artwork when a future handoff updates it. Use with `painterResource(ElrondIcons.Pen)`.
 *
 * The full set is registered (incl. tools not yet wired — Highlighter, Pencil, EraserPencil, Text,
 * Import, Record) so future tools can adopt the matching glyph instantly. The `*Tip` variants back
 * the FA-14 Body/Tip [PenIconStyle] tweak; resolve a pen-family tool with [penToolIcon].
 */
object ElrondIcons {
    @DrawableRes val Pen = R.drawable.ic_pen
    @DrawableRes val PenTip = R.drawable.ic_pen_tip
    @DrawableRes val Highlighter = R.drawable.ic_highlighter
    @DrawableRes val HighlighterTip = R.drawable.ic_highlighter_tip
    @DrawableRes val Pencil = R.drawable.ic_pencil
    @DrawableRes val PencilTip = R.drawable.ic_pencil_tip
    @DrawableRes val Eraser = R.drawable.ic_eraser
    @DrawableRes val EraserPencil = R.drawable.ic_eraser_pencil
    @DrawableRes val Text = R.drawable.ic_text
    @DrawableRes val Lasso = R.drawable.ic_lasso
    @DrawableRes val Import = R.drawable.ic_import
    @DrawableRes val Add = R.drawable.ic_add
    @DrawableRes val Record = R.drawable.ic_record
    @DrawableRes val Hand = R.drawable.ic_hand
    @DrawableRes val Checklist = R.drawable.ic_checklist
    @DrawableRes val NewNote = R.drawable.ic_new_note
    @DrawableRes val Pages = R.drawable.ic_pages
    @DrawableRes val Folder = R.drawable.ic_folder
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val MoreVert = R.drawable.ic_more_vert
    @DrawableRes val Undo = R.drawable.ic_undo
    @DrawableRes val Redo = R.drawable.ic_redo

    /** Resolves a pen-family tool's glyph for the chosen [PenIconStyle] (Body vs Tip). */
    @DrawableRes
    fun penToolIcon(body: Int, tip: Int, style: PenIconStyle): Int =
        if (style == PenIconStyle.TIP) tip else body
}
