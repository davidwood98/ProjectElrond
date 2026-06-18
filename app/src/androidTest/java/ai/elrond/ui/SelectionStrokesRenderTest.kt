package ai.elrond.ui

import ai.elrond.canvas.LiveTransform
import ai.elrond.canvas.StrokeTransforms
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-SCREEN render test for the FA-10 lasso live move + ghost. Instrumented because it touches ink
 * natives (`Stroke`, `CanvasStrokeRenderer` — which uses `Canvas.drawMesh`, hardware-only) AND real
 * Compose rasterisation; neither loads under JVM/Robolectric. Run on a device
 * (`connectedDebugAndroidTest`) — not runnable from WSL.
 *
 * This is the coverage that was missing and let the live move ship broken: the selected strokes are
 * drawn by [SelectionStrokes] and moved by a `graphicsLayer`; the rasterised ink must actually shift
 * by the drag distance. We render over a known white background and detect the (dark navy) ink, then
 * assert its bounding box moves. (The earlier version detected pixels by alpha, which the opaque test
 * host background defeated.)
 */
@RunWith(AndroidJUnit4::class)
class SelectionStrokesRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liveTransform_movesTheRasterisedInk_byTheDragDistance() {
        val stroke = buildStroke()
        var transform by mutableStateOf(LiveTransform.IDENTITY)
        composeRule.setContent {
            Surface {
                SelectionStrokes(selected = listOf(stroke), ghost = emptyList(), transform = transform)
            }
        }

        composeRule.waitForIdle()
        val origin = inkBounds() ?: error("no ink rendered at the origin position")

        transform = LiveTransform(dx = SHIFT_PX, dy = 0f)
        composeRule.waitForIdle()
        val moved = inkBounds()
            ?: error("no ink rendered after the move — the live transform never reached the screen")

        // The rasterised ink shifted right by ~SHIFT_PX: proves the live move renders on-screen.
        assertEquals(origin.minX + SHIFT_PX, moved.minX.toFloat(), TOL)
        assertEquals(origin.maxX + SHIFT_PX, moved.maxX.toFloat(), TOL)
    }

    @Test
    fun ghost_rendersAtOrigin_onlyWhileTransforming() {
        val ghost = listOf(StrokeTransforms.recolorStroke(buildStroke(), GHOST_COLOR))
        var transform by mutableStateOf(LiveTransform.IDENTITY)
        composeRule.setContent {
            Surface {
                // selected empty isolates the ghost; the ghost draws only while a transform is active.
                SelectionStrokes(selected = emptyList(), ghost = ghost, transform = transform)
            }
        }

        composeRule.waitForIdle()
        assertNull("ghost must not render while the selection is idle", inkBounds())

        transform = LiveTransform(dx = SHIFT_PX, dy = 0f)
        composeRule.waitForIdle()
        assertNotNull("ghost must render at the origin while transforming", inkBounds())
    }

    /** Bounding box (px) of ink pixels (notably darker than the white background), or null if none. */
    private fun inkBounds(): Bounds? {
        val px = composeRule.onRoot().captureToImage().toPixelMap()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        val yLimit = minOf(px.height, Y_SCAN_LIMIT)
        for (y in 0 until yLimit) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                // White background sums to ~3.0; navy ink (and its faded ghost) sum well below.
                if (c.red + c.green + c.blue < INK_SUM_MAX) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else Bounds(minX, minY, maxX, maxY)
    }

    private data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun buildStroke(): Stroke {
        val batch = MutableStrokeInputBatch()
        POINTS.forEachIndexed { i, (x, y) ->
            batch.addOrIgnore(
                type = InputToolType.STYLUS,
                x = x,
                y = y,
                elapsedTimeMillis = i * 8L,
                pressure = 0.6f,
                tiltRadians = 0.1f,
                orientationRadians = 0.2f,
            )
        }
        val brush = Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePenLatest,
            colorIntArgb = INK_COLOR,
            size = 6f,
            epsilon = 0.1f,
        )
        return Stroke(brush, batch)
    }

    /** A white-backed host so ink is distinguishable from the background. */
    @androidx.compose.runtime.Composable
    private fun Surface(content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(Color.White),
        ) { content() }
    }

    private companion object {
        const val SHIFT_PX = 300f
        const val TOL = 12f // brush half-width + AA slack; the shift applies to both ends equally
        const val INK_SUM_MAX = 2.5f
        const val Y_SCAN_LIMIT = 400
        val INK_COLOR = 0xFF1A237E.toInt()
        val GHOST_COLOR = (0x4D shl 24) or (INK_COLOR and 0x00FFFFFF) // ~30% alpha navy
        val POINTS = listOf(40f to 80f, 90f to 92f, 140f to 86f, 190f to 96f, 240f to 90f)
    }
}
