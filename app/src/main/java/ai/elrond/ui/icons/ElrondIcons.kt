package ai.elrond.ui.icons

import ai.elrond.R
import androidx.annotation.DrawableRes

/**
 * Central registry of the bespoke note-tool / action icons from the Claude Design handoff
 * (`res/drawable/ic_*.xml`). One typed place to reference the set — and the single place to swap
 * artwork when a future handoff updates it. Use with `painterResource(ElrondIcons.Pen)`.
 *
 * The full set is registered (incl. tools not yet wired — Highlighter, Pencil, EraserPencil, Text,
 * Import, Record) so future tools can adopt the matching glyph instantly.
 */
object ElrondIcons {
    @DrawableRes val Pen = R.drawable.ic_pen
    @DrawableRes val Highlighter = R.drawable.ic_highlighter
    @DrawableRes val Pencil = R.drawable.ic_pencil
    @DrawableRes val Eraser = R.drawable.ic_eraser
    @DrawableRes val EraserPencil = R.drawable.ic_eraser_pencil
    @DrawableRes val Text = R.drawable.ic_text
    @DrawableRes val Lasso = R.drawable.ic_lasso
    @DrawableRes val Import = R.drawable.ic_import
    @DrawableRes val Record = R.drawable.ic_record
    @DrawableRes val Hand = R.drawable.ic_hand
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val MoreVert = R.drawable.ic_more_vert
    @DrawableRes val Undo = R.drawable.ic_undo
    @DrawableRes val Redo = R.drawable.ic_redo
}
