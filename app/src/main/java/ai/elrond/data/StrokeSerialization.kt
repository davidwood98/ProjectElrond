package ai.elrond.data

import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.StrokeSimplifier
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.nio.ByteBuffer
import java.util.Base64
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
            inputsJson = encodeInputs(points),
            createdAt = createdAt,
            isAiInk = isAiInk,
            groupId = groupId,
        )
    }

    /**
     * Reconstructs a [CanvasStroke] (stable id + group membership + ink) from a stored row.
     *
     * [minSpacing] > 0 decimates the stored points to that page-space spacing when rebuilding the mesh
     * (debug perf knob) — an IN-MEMORY simplification only: the stored row is never rewritten, so it's
     * fully reversible by lowering the setting. Fewer points ⇒ cheaper mesh + lighter memory.
     */
    fun toCanvasStroke(entity: StrokeEntity, minSpacing: Float = 0f): CanvasStroke =
        CanvasStroke(id = entity.id, stroke = toStroke(entity, minSpacing), groupId = entity.groupId)

    fun toStroke(entity: StrokeEntity, minSpacing: Float = 0f): Stroke {
        var points = decodeInputs(entity.inputsJson)
        if (minSpacing > 0f && points.size > 2) {
            val keep = StrokeSimplifier.keptIndices(points.map { it.x to it.y }, minSpacing)
            points = keep.map { points[it] }
        }
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
        decodeInputs(inputsJson).map { it.x to it.y }

    // --- Point (de)serialization ---------------------------------------------------------------
    // Points are the bulk of the DB (a dense page is >100k points). The legacy format was a JSON
    // array (~90-100 bytes/point: full float text + a repeated "tool" string). The compact format
    // packs them binary (25 bytes/point) then Base64s into the same TEXT column, ~3× smaller — so
    // smaller files, faster DB reads and less to parse on load. Reads auto-detect: a JSON payload
    // starts with '[', so anything else is compact — old rows keep working with no migration; new
    // writes are compact. java.util.Base64/ByteBuffer are pure-JVM (Android 26+), so unit-testable.

    private const val BYTES_PER_POINT = 25 // tool(1) + x,y,t,pressure,tilt,orientation (6 × 4)

    internal fun encodeInputs(points: List<SerializedStrokeInput>): String {
        val buf = ByteBuffer.allocate(4 + points.size * BYTES_PER_POINT)
        buf.putInt(points.size)
        points.forEach { p ->
            buf.put(toolToByte(p.tool))
            buf.putFloat(p.x)
            buf.putFloat(p.y)
            buf.putInt(p.t.toInt()) // elapsed-within-stroke millis — always fits Int
            buf.putFloat(p.pressure)
            buf.putFloat(p.tilt)
            buf.putFloat(p.orientation)
        }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** Decodes either the legacy JSON array (starts with '[') or the compact Base64 packing. */
    internal fun decodeInputs(encoded: String): List<SerializedStrokeInput> {
        if (encoded.isEmpty()) return emptyList()
        if (encoded[0] == '[') return json.decodeFromString<List<SerializedStrokeInput>>(encoded)
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
        val count = buf.int
        val out = ArrayList<SerializedStrokeInput>(count)
        repeat(count) {
            val tool = toolFromByte(buf.get())
            out.add(
                SerializedStrokeInput(
                    x = buf.float,
                    y = buf.float,
                    t = buf.int.toLong(),
                    pressure = buf.float,
                    tilt = buf.float,
                    orientation = buf.float,
                    tool = tool,
                ),
            )
        }
        return out
    }

    private fun toolToByte(key: String): Byte = when (key) {
        "stylus" -> 0
        "touch" -> 1
        "mouse" -> 2
        else -> 3
    }

    private fun toolFromByte(b: Byte): String = when (b.toInt()) {
        0 -> "stylus"
        1 -> "touch"
        2 -> "mouse"
        else -> "unknown"
    }

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
