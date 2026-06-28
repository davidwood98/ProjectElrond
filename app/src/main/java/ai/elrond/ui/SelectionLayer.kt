package ai.elrond.ui

import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.Corner
import ai.elrond.domain.LiveTransform
import ai.elrond.domain.PageTransform
import ai.elrond.domain.SelectionState
import ai.elrond.domain.StrokeSelection
import ai.elrond.domain.StrokeTransforms
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * The lasso selection overlay (FA-9), shown above the ink canvas while the lasso tool is active. It
 * owns all pointer input in that mode so the canvas never draws ink:
 *
 *  - empty canvas: drag = draw a lasso (select the enclosed strokes); tap = paste (when the
 *    clipboard is armed) or deselect;
 *  - a selection: a dashed bounding box that drags to **move** and has corner handles to **scale**,
 *    a floating toolbar (Duplicate / Delete / AI), and a ⋮ kebab (Copy / Cut / Lock ratio /
 *    Group | Ungroup);
 *  - the clipboard banner pinned to the bottom while the clipboard holds anything.
 *
 * The actual ink moves/scales live in [ai.elrond.ui.InkCanvas]'s selection layer (a render
 * matrix); this overlay only draws the box/handles/toolbar and routes gestures to the ViewModel.
 */
@Composable
fun SelectionLayer(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val finishedStrokes by viewModel.finishedStrokes.collectAsStateWithLifecycle()
    // Page → screen transform (FA-20): strokes are stored in page coords, and the page may be
    // centred (landscape margins) and/or scrolled (you can't scroll in lasso mode, but you may have
    // scrolled in pen mode first). The overlay renders the box/ink page → screen and captures the
    // lasso screen → page through this transform.
    val transform by viewModel.pageTransform.collectAsStateWithLifecycle()
    // Palm rejection applies to the lasso too (FA-20): when stylus-only is on, a finger must NOT
    // start a selection — the lasso follows the same rule as the pen for all tools.
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    // Container size in px — clamps the floating menu on-screen.
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layerSize = it },
    ) {
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
            onSwipe = viewModel::swipeBy,
            onSwipeRelease = viewModel::releaseSwipe,
        )

        selection?.let { sel ->
            // The selected ink lives here (not in InkCanvas): the live move/scale + faded origin
            // ghost, drawn with the ink renderer in this Compose layer so it repaints every frame
            // (FA-10). Selected/ghost copies are built once per selection set; the box draws on top.
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

        if (clipboard.active) {
            ClipboardBar(
                count = clipboard.count,
                onClear = viewModel::clearClipboard,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

/** Dashed bounding box: drag to move, corner handles to scale, with the floating toolbar above. */
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

    with(density) {
        Box(
            Modifier
                .absoluteOffset(
                    x = transform.pageToScreenX(box.left).toDp(),
                    y = transform.pageToScreenY(box.top).toDp(),
                )
                .size(width = box.width.coerceAtLeast(1f).toDp(), height = box.height.coerceAtLeast(1f).toDp())
                .border(
                    width = 1.5.dp,
                    color = accent,
                    shape = RoundedCornerShape(4.dp),
                )
                // Drag the box body to move the selection.
                .pointerInput(sel.ids) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = { viewModel.commitTransform() },
                        onDragCancel = { viewModel.cancelTransform() },
                    ) { change, drag ->
                        change.consume()
                        dx += drag.x
                        dy += drag.y
                        viewModel.previewTransform(LiveTransform(dx = dx, dy = dy))
                    }
                },
        ) {
            ScaleHandle(Corner.TOP_LEFT, sel, viewModel, Modifier.align(Alignment.TopStart))
            ScaleHandle(Corner.TOP_RIGHT, sel, viewModel, Modifier.align(Alignment.TopEnd))
            ScaleHandle(Corner.BOTTOM_LEFT, sel, viewModel, Modifier.align(Alignment.BottomStart))
            ScaleHandle(Corner.BOTTOM_RIGHT, sel, viewModel, Modifier.align(Alignment.BottomEnd))
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
    modifier: Modifier,
) {
    val accent = LeapTheme.tokens.accent
    Box(
        modifier
            .size(HANDLE_SIZE.dp)
            .background(accent, CircleShape)
            .pointerInput(sel.ids, corner, sel.lockRatio) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = { viewModel.commitTransform() },
                    onDragCancel = { viewModel.cancelTransform() },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                    viewModel.previewTransform(
                        StrokeSelection.scaleTransform(corner, sel.bounds, dx, dy, sel.lockRatio),
                    )
                }
            },
    )
}

/**
 * Floating action bar for the selection: Duplicate / Delete / AI + a ⋮ kebab. Positioned
 * dynamically so it is always fully on-screen (project rule): centred over the selection and
 * clamped to the container's edges — it shifts left/right when the selection is near a side edge,
 * and flips above↔below (then clamps) when near the top/bottom. Uses the measured toolbar +
 * container sizes ([layerSize]), so the clamp is exact rather than guessed.
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
            TextButton(onClick = viewModel::duplicateSelection) { Text("Duplicate") }
            TextButton(onClick = viewModel::deleteSelection) { Text("Delete") }
            TextButton(onClick = viewModel::aiPromptSelection) {
                AiLogo(modifier = Modifier.size(20.dp), contentDescription = "Ask AI")
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = { menuOpen = false; viewModel.copySelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("Cut") },
                        onClick = { menuOpen = false; viewModel.cutSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("Lock ratio") },
                        trailingIcon = { if (sel.lockRatio) Text("✓") },
                        onClick = { viewModel.setLockRatio(!sel.lockRatio); menuOpen = false },
                    )
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
                text = if (count == 1) "1 stroke copied — tap to place" else "$count strokes copied — tap to place",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onClear) { Text("Clear clipboard") }
        }
    }
}

private const val HANDLE_SIZE = 18

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
