package ai.elrond.domain

import kotlin.math.sqrt

/**
 * Maps between a notebook page's fixed, **device-independent logical coordinate space** and
 * on-screen pixels (FA-20, the multi-page Notebooks foundation).
 *
 * Strokes, AI notes, lasso geometry, and `/Q` trigger hit-tests are all stored and computed in
 * logical page space — a portrait A-series rectangle ([LOGICAL_WIDTH] × [LOGICAL_HEIGHT]) that
 * never changes with the device, orientation, scroll, or zoom. This transform is the single place
 * that knows how that page is currently scaled and positioned on screen: by default the page is
 * fit to the screen width, then shifted by scroll/pan and (Phase 5) multiplied by a pinch-zoom
 * factor. Keeping the page space fixed is what lets a note open identically on any screen and
 * makes scroll + zoom a pure rendering concern.
 *
 * Convention: `screen = logical * `[scale]` + offset`. Pure and Compose-free so it is fully
 * unit-testable (the rendering layers apply the equivalent `graphicsLayer` / `Matrix`).
 *
 * Scope note: this core handles uniform scale + translation (fit-to-width, scroll, zoom). The
 * per-notebook landscape **view orientation** (a rigid 90° page rotation) is layered on later and
 * will extend this type; it deliberately is not modelled here yet.
 */
data class PageTransform(
    /** Uniform px-per-logical-unit scale. Fit-to-width = screenWidth / [LOGICAL_WIDTH]. */
    val scale: Float,
    /** Screen x of the page's logical origin (0, 0) — the horizontal centring margin (FA-20). */
    val offsetX: Float,
    /** Screen y of the page's logical origin (0, 0). */
    val offsetY: Float,
    /**
     * Transient horizontal page-turn slide (px), added to the on-screen x **without** affecting
     * [offsetX] (FA-20). Keeping it separate lets render sites that derive the page *width* from the
     * symmetric centring margin (`screenWidth − 2·offsetX`) stay correct mid-swipe, while the page
     * still visually slides. 0 at rest.
     */
    val panX: Float = 0f,
) {
    fun pageToScreenX(x: Float): Float = x * scale + offsetX + panX

    fun pageToScreenY(y: Float): Float = y * scale + offsetY

    fun screenToPageX(x: Float): Float = (x - offsetX - panX) / scale

    fun screenToPageY(y: Float): Float = (y - offsetY) / scale

    /** Convert a page-space length (radius, threshold, brush size) to screen pixels. */
    fun pageToScreenLength(length: Float): Float = length * scale

    /** Convert a screen-space length to page-space units. */
    fun screenToPageLength(length: Float): Float = length / scale

    companion object {
        /** Logical page width in device-independent units; height derives from the A-ratio. */
        const val LOGICAL_WIDTH = 1000f

        /** A-series aspect ratio (portrait): height = width × √2 ≈ 1.41421. */
        val ASPECT_RATIO: Float = sqrt(2f)

        /** Logical page height (portrait A-ratio). */
        val LOGICAL_HEIGHT: Float = LOGICAL_WIDTH * ASPECT_RATIO

        /**
         * A fit-to-width transform: the page spans [screenWidth] horizontally with its top-left
         * placed at ([originX], [originY]) in screen pixels — e.g. docked below the toolbar and
         * shifted upward by the current scroll offset.
         */
        fun fitToWidth(screenWidth: Float, originX: Float = 0f, originY: Float = 0f): PageTransform =
            PageTransform(scale = screenWidth / LOGICAL_WIDTH, offsetX = originX, offsetY = originY)

        /** On-screen height of a fit-to-width page for the given screen width. */
        fun pageScreenHeight(screenWidth: Float): Float =
            (screenWidth / LOGICAL_WIDTH) * LOGICAL_HEIGHT
    }
}
