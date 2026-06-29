package ai.elrond.domain

/**
 * One rendered page of a notebook in the editor's continuous document (FA-20 vertical scroll). In
 * horizontal mode the document is a single layer; in vertical-continuous mode every page is a layer
 * stacked top-to-bottom with a margin break between them.
 *
 * [docTopPx] is the page's top in **page-space** (unscaled) within the document; the renderer maps it
 * to the screen through the live [PageTransform] (`screenTop = transform.offsetY + docTopPx ·
 * transform.scale`). [strokes] are this page's dry strokes, stored in that page's own page-space.
 */
data class PageLayer(
    val pageId: String,
    val index: Int,
    val docTopPx: Float,
    val strokes: List<CanvasStroke>,
)
