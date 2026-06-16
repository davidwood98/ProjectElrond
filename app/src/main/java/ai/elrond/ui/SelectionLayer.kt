package ai.elrond.ui

import ai.elrond.ai.GestureTriggerDetector
import ai.elrond.canvas.CanvasViewModel
import ai.elrond.canvas.Corner
import ai.elrond.canvas.LiveTransform
import ai.elrond.canvas.SelectionState
import ai.elrond.canvas.StrokeSelection
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/** Lasso-selection accent — a blue distinct from user navy ink and AI violet. */
private val SelectionColor = Color(0xFF1565C0)

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
 * The actual ink moves/scales live in [ai.elrond.canvas.InkCanvas]'s selection layer (a render
 * matrix); this overlay only draws the box/handles/toolbar and routes gestures to the ViewModel.
 */
@Composable
fun SelectionLayer(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    // Container size in px (== canvas/stroke coordinate space) — clamps the floating menu on-screen.
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layerSize = it },
    ) {
        // Background: drag → new lasso; tap → paste (armed) or deselect.
        LassoCatcher(
            clipboardActive = clipboard.active,
            onLasso = viewModel::selectByLasso,
            onTap = { x, y ->
                if (clipboard.active) viewModel.pasteAt(x, y) else viewModel.clearSelection()
            },
        )

        selection?.let { sel -> SelectionBox(sel, viewModel, layerSize) }

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
    onLasso: (List<GestureTriggerDetector.Point>) -> Unit,
    onTap: (Float, Float) -> Unit,
) {
    val path = remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(clipboardActive) {
                awaitEachGesture {
                    val down = awaitFirstDown()
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
                        onLasso(points.map { GestureTriggerDetector.Point(it.x, it.y) })
                    } else {
                        onTap(down.position.x, down.position.y)
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
                    color = SelectionColor,
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
private fun SelectionBox(sel: SelectionState, viewModel: CanvasViewModel, layerSize: IntSize) {
    val density = LocalDensity.current
    val box = sel.displayBounds

    with(density) {
        Box(
            Modifier
                .absoluteOffset(x = box.left.toDp(), y = box.top.toDp())
                .size(width = box.width.coerceAtLeast(1f).toDp(), height = box.height.coerceAtLeast(1f).toDp())
                .border(
                    width = 1.5.dp,
                    color = SelectionColor,
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

    SelectionToolbar(sel, viewModel, layerSize)
}

/** A draggable corner handle that scales the selection about the opposite corner. */
@Composable
private fun ScaleHandle(
    corner: Corner,
    sel: SelectionState,
    viewModel: CanvasViewModel,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(HANDLE_SIZE.dp)
            .background(SelectionColor, CircleShape)
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
private fun SelectionToolbar(sel: SelectionState, viewModel: CanvasViewModel, layerSize: IntSize) {
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
                // Centre over the selection, then keep both horizontal edges on-screen.
                val maxX = (layerSize.width - w - margin).coerceAtLeast(margin)
                val x = (box.centerX - w / 2f).coerceIn(margin, maxX)
                // Prefer above the box; drop below when there's no room, then clamp vertically.
                val above = box.top - h - gap
                val rawY = if (above >= margin) above else box.bottom + gap
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
            TextButton(onClick = viewModel::aiPromptSelection) { Text("AI") }
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
