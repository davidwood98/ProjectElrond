package ai.elrond.canvas

import ai.elrond.ai.GestureTriggerDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned bounds of a lasso selection, in canvas pixels. */
data class SelectionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * A live move/scale previewed on the current selection: scale about ([pivotX], [pivotY]) first,
 * then translate by ([dx], [dy]). [IDENTITY] means no change. Pure data — both the render layer
 * (as an Android `Matrix`) and the bake step ([StrokeTransforms.transformStroke]) read it.
 */
data class LiveTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = dx == 0f && dy == 0f && scaleX == 1f && scaleY == 1f

    fun applyX(x: Float): Float = pivotX + (x - pivotX) * scaleX + dx
    fun applyY(y: Float): Float = pivotY + (y - pivotY) * scaleY + dy

    /** Uniform brush-size scale factor for this transform (geometric mean of the axes). */
    val brushScale: Float
        get() = sqrt(abs(scaleX * scaleY)).let { if (it <= 0f) 1f else it }

    companion object {
        val IDENTITY = LiveTransform()
    }
}

/**
 * Pure selection geometry for the lasso tool (no ink natives — JVM-testable). Hit-testing reuses
 * [GestureTriggerDetector]; the ink-touching parts (centroids, per-stroke bounds, transforms) live
 * in [StrokeTransforms] and are injected into [CanvasViewModel] as seams.
 */
object StrokeSelection {

    /**
     * Expands a raw selection to whole groups: if any selected stroke belongs to a group, every
     * other stroke in that group is pulled in too (select one → select all). Ungrouped strokes are
     * unaffected.
     */
    fun expandToGroups(selectedIds: Set<String>, strokes: List<CanvasStroke>): Set<String> {
        if (selectedIds.isEmpty()) return selectedIds
        val touchedGroups = strokes
            .filter { it.id in selectedIds }
            .mapNotNull { it.groupId }
            .toSet()
        if (touchedGroups.isEmpty()) return selectedIds
        return selectedIds + strokes.filter { it.groupId in touchedGroups }.map { it.id }
    }

    /** Ids of the strokes whose [centroids] (index-aligned with [ids]) fall inside the [polygon]. */
    fun enclosedIds(
        polygon: List<GestureTriggerDetector.Point>,
        ids: List<String>,
        centroids: List<GestureTriggerDetector.Point>,
    ): Set<String> =
        GestureTriggerDetector.enclosedIndices(polygon, centroids).map { ids[it] }.toSet()

    /** Union of per-stroke bounds; null when [boxes] is empty. */
    fun union(boxes: List<SelectionBounds>): SelectionBounds? =
        boxes.reduceOrNull { a, b ->
            SelectionBounds(
                left = min(a.left, b.left),
                top = min(a.top, b.top),
                right = max(a.right, b.right),
                bottom = max(a.bottom, b.bottom),
            )
        }

    /**
     * The [LiveTransform] for dragging the [corner] handle of [bounds] by ([dragX], [dragY]),
     * scaling about the opposite corner. Per-axis when [lockRatio] is false; uniform (diagonal
     * distance ratio) when true. Scale is floored at [MIN_SCALE] so the box can't collapse or flip.
     */
    fun scaleTransform(
        corner: Corner,
        bounds: SelectionBounds,
        dragX: Float,
        dragY: Float,
        lockRatio: Boolean,
    ): LiveTransform {
        val onLeft = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
        val onTop = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
        val pivotX = if (onLeft) bounds.right else bounds.left
        val pivotY = if (onTop) bounds.bottom else bounds.top
        val oldX = (if (onLeft) bounds.left else bounds.right) - pivotX
        val oldY = (if (onTop) bounds.top else bounds.bottom) - pivotY

        var scaleX = if (abs(oldX) < EPSILON) 1f else (oldX + dragX) / oldX
        var scaleY = if (abs(oldY) < EPSILON) 1f else (oldY + dragY) / oldY
        if (lockRatio) {
            val oldDist = hypot(oldX, oldY)
            val s = if (oldDist < EPSILON) 1f else hypot(oldX + dragX, oldY + dragY) / oldDist
            scaleX = s
            scaleY = s
        }
        return LiveTransform(
            scaleX = scaleX.coerceAtLeast(MIN_SCALE),
            scaleY = scaleY.coerceAtLeast(MIN_SCALE),
            pivotX = pivotX,
            pivotY = pivotY,
        )
    }

    private const val EPSILON = 0.01f
    private const val MIN_SCALE = 0.05f
}

/** Which corner handle of the selection box is being dragged to scale. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * The current lasso selection surfaced to the UI: which strokes are selected ([ids]), the baseline
 * bounding box ([bounds], pre-live-transform), any in-progress move/scale ([transform]), whether
 * the aspect ratio is locked, and whether the whole selection is one existing group.
 */
data class SelectionState(
    val ids: Set<String>,
    val bounds: SelectionBounds,
    val transform: LiveTransform = LiveTransform.IDENTITY,
    val lockRatio: Boolean = false,
    val grouped: Boolean = false,
) {
    val count: Int get() = ids.size

    /** The box as currently shown — baseline [bounds] with the live [transform] applied. */
    val displayBounds: SelectionBounds
        get() = SelectionBounds(
            left = transform.applyX(bounds.left),
            top = transform.applyY(bounds.top),
            right = transform.applyX(bounds.right),
            bottom = transform.applyY(bounds.bottom),
        )

    /** Grouping is offered only for ≥2 strokes that aren't already a single group. */
    val canGroup: Boolean get() = ids.size >= 2 && !grouped
}

/** Clipboard banner state for the UI: how many strokes are held, and whether paste is armed. */
data class ClipboardState(val count: Int = 0) {
    val active: Boolean get() = count > 0

    companion object {
        val EMPTY = ClipboardState()
    }
}

