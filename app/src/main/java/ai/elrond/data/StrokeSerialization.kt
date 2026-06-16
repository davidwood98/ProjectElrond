package ai.elrond.data

import ai.elrond.canvas.CanvasStroke
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialization between ink [Stroke]s and [StrokeEntity] rows.
 *
 * A stroke is persisted as its brush parameters plus the raw input points; the stroke
 * mesh is regenerated from those inputs on load.
 */
object StrokeSerialization {

    private val json = Json { ignoreUnknownKeys = true }

    private const val FAMILY_PRESSURE_PEN = "pressure-pen"
    private const val FAMILY_MARKER = "marker"
    private const val FAMILY_HIGHLIGHTER = "highlighter"

    fun toEntity(
        stroke: Stroke,
        id: String,
        pageId: String,
        createdAt: Long,
        isAiInk: Boolean = false,
        groupId: String? = null,
    ): StrokeEntity {
        val points = ArrayList<SerializedStrokeInput>(stroke.inputs.size)
        val scratch = StrokeInput()
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            points.add(
                SerializedStrokeInput(
                    x = scratch.x,
                    y = scratch.y,
                    t = scratch.elapsedTimeMillis,
                    pressure = scratch.pressure,
                    tilt = scratch.tiltRadians,
                    orientation = scratch.orientationRadians,
                    tool = toolKey(scratch.toolType),
                ),
            )
        }
        return StrokeEntity(
            id = id,
            pageId = pageId,
            brushFamily = familyKey(stroke.brush.family),
            colorArgb = stroke.brush.colorIntArgb,
            brushSize = stroke.brush.size,
            brushEpsilon = stroke.brush.epsilon,
            inputsJson = json.encodeToString(points),
            createdAt = createdAt,
            isAiInk = isAiInk,
            groupId = groupId,
        )
    }

    /** Reconstructs a [CanvasStroke] (stable id + group membership + ink) from a stored row. */
    fun toCanvasStroke(entity: StrokeEntity): CanvasStroke =
        CanvasStroke(id = entity.id, stroke = toStroke(entity), groupId = entity.groupId)

    fun toStroke(entity: StrokeEntity): Stroke {
        val points = json.decodeFromString<List<SerializedStrokeInput>>(entity.inputsJson)
        val batch = MutableStrokeInputBatch()
        points.forEach { p ->
            // addOrIgnore skips a point that would be invalid relative to the batch (e.g. a
            // non-increasing timestamp) instead of throwing, so a reloaded stroke keeps every
            // valid point. (FA-8: ink 1.0.0's add() threw on those points and collapsed reloaded
            // strokes to a single dot — see CLAUDE.md; pinned back to 1.0.0-alpha04.)
            batch.addOrIgnore(
                type = toolFromKey(p.tool),
                x = p.x,
                y = p.y,
                elapsedTimeMillis = p.t,
                pressure = p.pressure,
                tiltRadians = p.tilt,
                orientationRadians = p.orientation,
            )
        }
        val brush = Brush.createWithColorIntArgb(
            family = familyFromKey(entity.brushFamily),
            colorIntArgb = entity.colorArgb,
            size = entity.brushSize,
            epsilon = entity.brushEpsilon,
        )
        return Stroke(brush, batch)
    }

    /** Raw (x, y) polyline from a stored stroke — for thumbnails; no ink natives. */
    fun decodePoints(inputsJson: String): List<Pair<Float, Float>> =
        json.decodeFromString<List<SerializedStrokeInput>>(inputsJson).map { it.x to it.y }

    private fun familyKey(family: BrushFamily): String = when (family) {
        StockBrushes.markerLatest -> FAMILY_MARKER
        StockBrushes.highlighterLatest -> FAMILY_HIGHLIGHTER
        else -> FAMILY_PRESSURE_PEN
    }

    private fun familyFromKey(key: String): BrushFamily = when (key) {
        FAMILY_MARKER -> StockBrushes.markerLatest
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighterLatest
        else -> StockBrushes.pressurePenLatest
    }

    private fun toolKey(type: InputToolType): String = when (type) {
        InputToolType.STYLUS -> "stylus"
        InputToolType.TOUCH -> "touch"
        InputToolType.MOUSE -> "mouse"
        else -> "unknown"
    }

    private fun toolFromKey(key: String): InputToolType = when (key) {
        "stylus" -> InputToolType.STYLUS
        "touch" -> InputToolType.TOUCH
        "mouse" -> InputToolType.MOUSE
        else -> InputToolType.UNKNOWN
    }
}

/** Pure-data form of one stroke input point — JVM-testable without ink natives. */
@Serializable
data class SerializedStrokeInput(
    val x: Float,
    val y: Float,
    val t: Long,
    val pressure: Float,
    val tilt: Float,
    val orientation: Float,
    val tool: String = "stylus",
)
