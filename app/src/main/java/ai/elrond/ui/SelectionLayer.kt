package ai.elrond.ui

import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.Corner
import ai.elrond.domain.LiveTransform
import ai.elrond.domain.PageTransform
import ai.elrond.domain.SelectionState
import ai.elrond.domain.StrokeSelection
import ai.elrond.domain.StrokeTransforms
import ai.elrond.domain.safeScale
import ai.elrond.ui.theme.LeapTheme
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.key
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke as InkStroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * The lasso **input** overlay (FA-9), shown above the ink canvas only while the lasso tool is active.
 * It owns full-screen input in that mode so the canvas never draws ink:
 *
 *  - empty canvas: drag = draw a lasso (select the enclosed strokes + AI boxes); tap = paste (when
 *    the clipboard is armed) or deselect;
 *  - the clipboard banner pinned to the bottom while the clipboard holds anything.
 *
 * The selection **chrome** (the dashed box, resize handles, and floating toolbar) is drawn
 * separately by [SelectionDecorations], which renders for any selection in any tool (FA-21: an AI
 * box can be selected by a 1.5s press-and-hold while drawing). The moving ink renders in this
 * Compose layer with the ink renderer (FA-10); selected AI boxes scale live via their own
 * graphicsLayer in [AiInkNoteView].
 */
@Composable
fun SelectionLayer(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    // Page → screen transform (FA-20): strokes are stored in page coords, and the page may be
    // centred (landscape margins) and/or scrolled. The overlay captures the lasso screen → page
    // through this transform.
    val transform by viewModel.pageTransform.collectAsStateWithLifecycle()
    // Palm rejection applies to the lasso too (FA-20): when stylus-only is on, a finger must NOT
    // start a selection — the lasso follows the same rule as the pen for all tools.
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        // Background: drag → new lasso; tap → paste (armed) or deselect.
        LassoCatcher(
            clipboardActive = clipboard.active,
            transform = transform,
            stylusOnly = stylusOnly,
            onLasso = viewModel::selectByLasso,
            onTap = { x, y ->
                if (clipboard.active) viewModel.pasteAt(x, y) else viewModel.clearSelection()
            },
            // A finger rejected from lassoing (palm rejection) still scrolls / turns pages, so the
            // lasso tool behaves like every other tool for navigation (FA-20).
            onScroll = viewModel::scrollBy,
            onScrollRelease = viewModel::releaseScroll,
            onSwipe = viewModel::swipeBy,
            onSwipeRelease = viewModel::releaseSwipe,
        )

        if (clipboard.active) {
            ClipboardBar(
                count = clipboard.count,
                onClear = viewModel::clearClipboard,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The selection chrome (FA-21): the live-moving ink + faded ghost, the dashed box with resize
 * handles, and the floating toolbar. Drawn whenever something is selected — in **any** tool, not
 * just Lasso — because an AI box can be selected by a 1.5s press-and-hold while drawing. It owns no
 * full-screen input (empty areas fall through to the canvas, so the pen still writes); only the box,
 * its handles, and the toolbar capture gestures. In Lasso mode this sits above [SelectionLayer]'s
 * catcher so the handles stay tappable.
 */
@Composable
fun SelectionDecorations(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val finishedStrokes by viewModel.finishedStrokes.collectAsStateWithLifecycle()
    val transform by viewModel.pageTransform.collectAsStateWithLifecycle()
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layerSize = it },
    ) {
        selection?.let { sel ->
            // The selected ink lives here (not in InkCanvas): the live move/scale + faded origin
            // ghost, drawn with the ink renderer in this Compose layer so it repaints every frame
            // (FA-10). Selected AI boxes scale live via their own graphicsLayer in AiInkNoteView.
            val selectedStrokes = remember(sel.ids, finishedStrokes) {
                finishedStrokes.filter { it.id in sel.ids }.map { it.stroke }
            }
            val ghostStrokes = remember(sel.ids, finishedStrokes) {
                finishedStrokes.filter { it.id in sel.ids }.map {
                    StrokeTransforms.recolorStroke(it.stroke, fadedGhostColor(it.stroke.brush.colorIntArgb))
                }
            }
            SelectionStrokes(selectedStrokes, ghostStrokes, sel.transform, transform)
            SelectionBox(sel, viewModel, layerSize, transform)
        }
    }
}

/** Full-screen catcher: distinguishes a drag (lasso) from a tap, and draws the live lasso path. */
@Composable
private fun LassoCatcher(
    clipboardActive: Boolean,
    transform: PageTransform,
    stylusOnly: Boolean,
    onLasso: (List<GestureTriggerDetector.Point>) -> Unit,
    onTap: (Float, Float) -> Unit,
    onScroll: (Float) -> Unit,
    onScrollRelease: () -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeRelease: () -> Unit,
) {
    val accent = LeapTheme.tokens.accent
    val path = remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(clipboardActive, stylusOnly) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Palm rejection (FA-20): with stylus-only on, a finger must NOT lasso/paste — but
                    // it should still scroll / turn pages like every other tool. So instead of
                    // dropping the gesture, drive navigation with it (axis-locked after a small slop:
                    // vertical = scroll, horizontal = page turn), matching the InkCanvas finger path.
                    if (stylusOnly && down.type == PointerType.Touch) {
                        var axis = 0 // 0 = undecided, 1 = vertical scroll, 2 = horizontal page turn
                        var lastY = down.position.y
                        var lastX = down.position.x
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            val total = ch.position - down.position
                            if (axis == 0) {
                                val locked = lockAxisOrUndecided(total.x, total.y)
                                if (locked != 0) {
                                    axis = locked
                                    lastY = ch.position.y
                                    lastX = ch.position.x
                                }
                            }
                            if (axis == 1) {
                                onScroll(ch.position.y - lastY)
                                lastY = ch.position.y
                                ch.consume()
                            } else if (axis == 2) {
                                // Live page-turn slide (same as the pen path) — release decides.
                                onSwipe(ch.position.x - lastX)
                                lastX = ch.position.x
                                ch.consume()
                            }
                        }
                        if (axis == 2) onSwipeRelease()
                        if (axis == 1) onScrollRelease()
                        return@awaitEachGesture
                    }
                    val points = mutableListOf(down.position)
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        points.add(change.position)
                        if (!dragging &&
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            path.value = points.toList()
                            change.consume()
                        }
                    }
                    if (dragging) {
                        // Convert the screen-space lasso to page coords so it hit-tests against the
                        // stored (page-space) strokes (FA-20).
                        onLasso(
                            points.map {
                                GestureTriggerDetector.Point(
                                    transform.screenToPageX(it.x),
                                    transform.screenToPageY(it.y),
                                )
                            },
                        )
                    } else {
                        onTap(
                            transform.screenToPageX(down.position.x),
                            transform.screenToPageY(down.position.y),
                        )
                    }
                    path.value = emptyList()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pts = path.value
            if (pts.size > 1) {
                val outline = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(
                    path = outline,
                    color = accent,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                    ),
                )
            }
        }
    }
}

/** Dashed bounding box: drag to move, handles to resize, with the floating toolbar above. */
@Composable
private fun SelectionBox(
    sel: SelectionState,
    viewModel: CanvasViewModel,
    layerSize: IntSize,
    transform: PageTransform,
) {
    val density = LocalDensity.current
    val accent = LeapTheme.tokens.accent
    val box = sel.displayBounds
    // Map BOTH corners page → screen so the box is correctly sized at any zoom (FA-21).
    val leftPx = transform.pageToScreenX(box.left)
    val topPx = transform.pageToScreenY(box.top)
    val widthPx = (transform.pageToScreenX(box.right) - leftPx).coerceAtLeast(1f)
    val heightPx = (transform.pageToScreenY(box.bottom) - topPx).coerceAtLeast(1f)
    val half = HANDLE_SIZE / 2f
    val aiId = sel.aiNoteIds.firstOrNull()

    with(density) {
        Box(
            Modifier
                .absoluteOffset(x = leftPx.toDp(), y = topPx.toDp())
                .size(width = widthPx.toDp(), height = heightPx.toDp())
                .border(width = 1.5.dp, color = accent, shape = RoundedCornerShape(4.dp))
                // Drag the box body to move the selection (strokes + AI boxes together).
                .pointerInput(sel.ids, sel.aiNoteIds) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = { viewModel.commitTransform() },
                        onDragCancel = { viewModel.cancelTransform() },
                    ) { change, drag ->
                        change.consume()
                        // Drag is screen-space; the transform lives in page space, so divide out zoom.
                        val s = transform.safeScale
                        dx += drag.x / s
                        dy += drag.y / s
                        viewModel.previewTransform(LiveTransform(dx = dx, dy = dy))
                    }
                },
        ) {
            // Corner handles, centred ON each corner, scale the selection (ratio-locked by default,
            // which on an AI box grows/shrinks the font to keep the text fitting).
            ScaleHandle(Corner.TOP_LEFT, sel, viewModel, transform,
                Modifier.align(Alignment.TopStart).offset((-half).dp, (-half).dp))
            ScaleHandle(Corner.TOP_RIGHT, sel, viewModel, transform,
                Modifier.align(Alignment.TopEnd).offset(half.dp, (-half).dp))
            ScaleHandle(Corner.BOTTOM_LEFT, sel, viewModel, transform,
                Modifier.align(Alignment.BottomStart).offset((-half).dp, half.dp))
            ScaleHandle(Corner.BOTTOM_RIGHT, sel, viewModel, transform,
                Modifier.align(Alignment.BottomEnd).offset(half.dp, half.dp))

            // A lone AI box also gets left/right edge handles that reflow the width at a constant
            // font size (narrower box = the text wraps to more lines = taller). FA-21.
            if (sel.isSingleAiNote && aiId != null) {
                EdgeHandle(true, aiId, viewModel, transform,
                    Modifier.align(Alignment.CenterStart).offset(x = (-EDGE_THICKNESS / 2f).dp))
                EdgeHandle(false, aiId, viewModel, transform,
                    Modifier.align(Alignment.CenterEnd).offset(x = (EDGE_THICKNESS / 2f).dp))
            }
        }
    }

    SelectionToolbar(sel, viewModel, layerSize, transform)
}

/** A draggable corner handle that scales the selection about the opposite corner. */
@Composable
private fun ScaleHandle(
    corner: Corner,
    sel: SelectionState,
    viewModel: CanvasViewModel,
    transform: PageTransform,
    modifier: Modifier,
) {
    val accent = LeapTheme.tokens.accent
    Box(
        modifier
            .size(HANDLE_SIZE.dp)
            .background(accent, CircleShape)
            .pointerInput(sel.ids, sel.aiNoteIds, corner, sel.lockRatio) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = { viewModel.commitTransform() },
                    onDragCancel = { viewModel.cancelTransform() },
                ) { change, drag ->
                    change.consume()
                    val s = transform.safeScale
                    dx += drag.x / s
                    dy += drag.y / s
                    viewModel.previewTransform(
                        StrokeSelection.scaleTransform(corner, sel.bounds, dx, dy, sel.lockRatio),
                    )
                }
            },
    )
}

/** A left/right edge handle that reflows a lone AI box's width at a constant font size (FA-21). */
@Composable
private fun EdgeHandle(
    left: Boolean,
    noteId: String,
    viewModel: CanvasViewModel,
    transform: PageTransform,
    modifier: Modifier,
) {
    val accent = LeapTheme.tokens.accent
    Box(
        modifier
            .size(width = EDGE_THICKNESS.dp, height = EDGE_LENGTH.dp)
            .background(accent, RoundedCornerShape(EDGE_THICKNESS.dp))
            .pointerInput(noteId, left) {
                var startX = 0f
                var startWidth = 0f
                var dx = 0f
                detectDragGestures(
                    onDragStart = {
                        viewModel.beginAiNoteReflow() // one undo step for the whole reflow drag
                        val n = viewModel.aiNotes.value.firstOrNull { it.id == noteId }
                        startX = n?.x ?: 0f
                        startWidth = n?.widthPx ?: 0f
                        dx = 0f
                    },
                ) { change, drag ->
                    change.consume()
                    val s = transform.safeScale
                    dx += drag.x / s
                    if (left) {
                        // Left edge: the right edge stays put, so x and width move together.
                        viewModel.reflowAiNoteWidth(noteId, x = startX + dx, widthPx = startWidth - dx)
                    } else {
                        viewModel.reflowAiNoteWidth(noteId, x = startX, widthPx = startWidth + dx)
                    }
                }
            },
    )
}

/**
 * Floating action bar for the selection, positioned dynamically so it is always fully on-screen
 * (project rule): centred over the selection and clamped to the container's edges — it shifts
 * left/right near a side edge and flips above↔below near the top/bottom, using the measured toolbar
 * + container sizes ([layerSize]) for an exact clamp.
 *
 * Two variants (FA-21): a **stroke** selection shows Duplicate / Delete / AI + a ⋮ kebab (Copy / Cut
 * / lock / Group); a selection that **includes an AI box** drops the AI button and promotes Copy to
 * the top row (Copy / Delete + a kebab of Duplicate / Cut / lock).
 */
@Composable
private fun SelectionToolbar(
    sel: SelectionState,
    viewModel: CanvasViewModel,
    layerSize: IntSize,
    transform: PageTransform,
) {
    val box = sel.displayBounds
    var menuOpen by remember { mutableStateOf(false) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    val hasAi = sel.hasAiNote

    Surface(
        modifier = Modifier
            .offset {
                val w = toolbarSize.width
                val h = toolbarSize.height
                val margin = 8.dp.toPx()
                val gap = 8.dp.toPx()
                // Centre over the selection (in screen space), then keep both horizontal edges on.
                val maxX = (layerSize.width - w - margin).coerceAtLeast(margin)
                val x = (transform.pageToScreenX(box.centerX) - w / 2f).coerceIn(margin, maxX)
                // Prefer above the box; drop below when there's no room, then clamp vertically.
                val above = transform.pageToScreenY(box.top) - h - gap
                val rawY = if (above >= margin) above else transform.pageToScreenY(box.bottom) + gap
                val maxY = (layerSize.height - h - margin).coerceAtLeast(margin)
                val y = rawY.coerceIn(margin, maxY)
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .onSizeChanged { toolbarSize = it },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Copy & Duplicate swap between the top row and the kebab by variant (FA-21) — compute
            // each once so neither button is written twice.
            val topLabel = if (hasAi) "Copy" else "Duplicate"
            val topAction = if (hasAi) viewModel::copySelection else viewModel::duplicateSelection
            val kebabLabel = if (hasAi) "Duplicate" else "Copy"
            val kebabAction = if (hasAi) viewModel::duplicateSelection else viewModel::copySelection

            TextButton(onClick = topAction) { Text(topLabel) }
            TextButton(onClick = viewModel::deleteSelection) { Text("Delete") }
            if (!hasAi) {
                TextButton(onClick = viewModel::aiPromptSelection) {
                    AiLogo(modifier = Modifier.size(40.dp), contentDescription = "Ask AI")
                }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = KEBAB_GLYPH_SP.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(kebabLabel) },
                        onClick = { menuOpen = false; kebabAction() },
                    )
                    DropdownMenuItem(
                        text = { Text("Cut") },
                        onClick = { menuOpen = false; viewModel.cutSelection() },
                    )
                    DropdownMenuItem(
                        // Toggle shows the ACTION it performs (FA-21): default is locked.
                        text = { Text(if (sel.lockRatio) "Unlock aspect" else "Lock aspect") },
                        onClick = { viewModel.setLockRatio(!sel.lockRatio); menuOpen = false },
                    )
                    if (!hasAi) {
                        if (sel.grouped) {
                            DropdownMenuItem(
                                text = { Text("Ungroup") },
                                onClick = { menuOpen = false; viewModel.ungroupSelection() },
                            )
                        } else if (sel.canGroup) {
                            DropdownMenuItem(
                                text = { Text("Group") },
                                onClick = { menuOpen = false; viewModel.groupSelection() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Bottom banner shown while the clipboard holds strokes: status (left) + Clear clipboard (right). */
@Composable
private fun ClipboardBar(count: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (count == 1) "1 item copied — tap to place" else "$count items copied — tap to place",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onClear) { Text("Clear clipboard") }
        }
    }
}

private const val HANDLE_SIZE = 18

/** Left/right edge reflow handle dimensions (FA-21), dp. */
private const val EDGE_THICKNESS = 14
private const val EDGE_LENGTH = 44

/** The ⋮ kebab glyph size (FA-21) — ~2× the default so it reads at the toolbar text scale. */
private const val KEBAB_GLYPH_SP = 26

/** Origin-ghost opacity during a lasso move (FA-10): the original colour at 30%. */
private const val GHOST_ALPHA = 0.30f

/** [original] ARGB re-alpha'd to [GHOST_ALPHA], keeping its RGB — the faded-origin ghost colour. */
private fun fadedGhostColor(original: Int): Int {
    val alpha = (GHOST_ALPHA * 255f).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (original and 0x00FFFFFF)
}

/**
 * Draws the lasso-selected ink (FA-10): the [selected] strokes at the live move/scale [transform]
 * (full colour), and — while a transform is in progress — a faded [ghost] of them at their original
 * position. Rendered in the Compose overlay (not [ai.elrond.ui.InkCanvas]) with the same
 * [CanvasStrokeRenderer]; ghost/selected copies are built once per selection by the caller.
 *
 * The live transform is applied as a **`graphicsLayer`**, NOT inside the draw lambda. Compose does
 * not re-run a draw lambda just because a captured value changed, so a transform applied there left
 * the ink frozen at the origin (and the ghost pass never ran). `graphicsLayer` is the same
 * layer-modifier mechanism the selection box uses (`absoluteOffset`), which re-applies every frame —
 * and it's GPU-cheap: the strokes are rasterised once and the layer is just re-composited, so there
 * is no per-frame mesh redraw. The strokes Canvas is `key`ed on the stroke list so a new (or
 * just-baked) selection re-rasterises while a drag (same list) does not.
 *
 * `internal` (not private) so `SelectionStrokesRenderTest` can render it and pixel-assert the moved
 * ink actually appears at the new position.
 */
@Composable
internal fun SelectionStrokes(
    selected: List<InkStroke>,
    ghost: List<InkStroke>,
    transform: LiveTransform,
    page: PageTransform = PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    // Strokes are drawn at IDENTITY (page coordinates); the page → screen mapping (centring offset +
    // scroll + scale) is applied as a GPU graphicsLayer — the SAME mechanism the dry-ink layer uses
    // (InkCanvas.DryStrokesView translates a page-space view), so the live selection lands exactly
    // where the dry ink does in every orientation. Baking the offset into the draw matrix instead
    // mis-placed the live ink in landscape (FA-20 device feedback). The live move/scale is a second,
    // inner layer in page space — composed as page(live(stroke)) — re-applied per frame, no mesh redraw.
    val pageLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        translationX = page.offsetX + page.panX // panX = transient page-turn slide
        translationY = page.offsetY
        scaleX = page.scale
        scaleY = page.scale
        transformOrigin = TransformOrigin(0f, 0f)
    }
    // Faded origin ghost — drawn once at the original page position, shown only while transforming.
    if (!transform.isIdentity) {
        StrokeCanvas(ghost, renderer, Modifier.graphicsLayer(pageLayer))
    }
    key(selected) {
        StrokeCanvas(
            selected,
            renderer,
            Modifier
                // Outer: page → screen (applied last). Inner: the live move/scale in page space.
                .graphicsLayer(pageLayer)
                .graphicsLayer {
                    translationX = transform.dx
                    translationY = transform.dy
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                    // Live pivot is in page coords; the identity-drawn canvas is 1:1 page↔pixel.
                    transformOrigin = if (size.width > 0f && size.height > 0f) {
                        TransformOrigin(transform.pivotX / size.width, transform.pivotY / size.height)
                    } else {
                        TransformOrigin(0f, 0f)
                    }
                },
        )
    }
}

/** Rasterises [strokes] at IDENTITY (page coordinates); the caller's [modifier] maps page → screen. */
@Composable
private fun StrokeCanvas(
    strokes: List<InkStroke>,
    renderer: CanvasStrokeRenderer,
    modifier: Modifier,
) {
    val identity = remember { Matrix() }
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            strokes.forEach { stroke ->
                renderer.draw(canvas = nativeCanvas, stroke = stroke, strokeToScreenTransform = identity)
            }
        }
    }
}
