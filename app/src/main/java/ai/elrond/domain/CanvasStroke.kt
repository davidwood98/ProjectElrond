package ai.elrond.domain

import androidx.ink.strokes.Stroke

/**
 * One finished (dry) stroke on the canvas, with a stable in-memory [id] and optional [groupId].
 *
 * The raw ink [Stroke] is immutable and has no identity of its own, and a transform (move / scale)
 * rebuilds it into a brand-new `Stroke` object. Wrapping it gives the lasso selection a stable
 * handle that survives those transforms (selection tracks [id]s, not stroke references), and
 * carries group membership ([groupId]) both in memory and to/from storage so a group reloads
 * grouped. [groupId] is null for an ungrouped stroke; strokes sharing a non-null [groupId] form
 * one group (select one → select all).
 */
data class CanvasStroke(
    val id: String,
    val stroke: Stroke,
    val groupId: String? = null,
)
