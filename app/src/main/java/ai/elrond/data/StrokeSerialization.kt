package ai.elrond.data

import ai.elrond.domain.BrushSpec
import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.StrokeSimplifier
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.ExperimentalInkCustomBrushApi
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

    private const val FAMILY_PRESSURE_PEN = BrushSpec.FAMILY_PRESSURE_PEN
    private const val FAMILY_MARKER = "marker"
    private const val FAMILY_HIGHLIGHTER = BrushSpec.FAMILY_HIGHLIGHTER
    private const val FAMILY_PENCIL = BrushSpec.FAMILY_PENCIL

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
            inputs = encodeInputs(points),
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
        var points = decodeInputs(entity.inputs)
        if (minSpacing > 0f && points.size > 2) {
            val keep = StrokeSimplifier.keptIndices(points.map { it.x to it.y }, minSpacing)
            points = keep.map { points[it] }
        }
        val brush = Brush.createWithColorIntArgb(
            family = familyFromKey(entity.brushFamily),
            colorIntArgb = entity.colorArgb,
            size = entity.brushSize,
            epsilon = entity.brushEpsilon,
        )
        return Stroke(brush, inkBatchFrom(points))
    }

    /**
     * The ONE place stored points become an ink input batch. ink 1.0.0's `add` throws on a point
     * invalid relative to the batch (no skip-invalid variant exists), so the points are sanitized
     * first — after [StrokeInputSanitizer] a throw means a genuine bug, not bad stored data.
     */
    private fun inkBatchFrom(points: List<SerializedStrokeInput>): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        StrokeInputSanitizer.sanitize(points).forEach { p ->
            batch.add(
                type = toolFromKey(p.tool),
                x = p.x,
                y = p.y,
                elapsedTimeMillis = p.t,
                pressure = p.pressure,
                tiltRadians = p.tilt,
                orientationRadians = p.orientation,
            )
        }
        return batch
    }

    /** Raw (x, y) polyline from a stored stroke — for thumbnails; no ink natives. */
    fun decodePoints(inputs: ByteArray): List<Pair<Float, Float>> =
        decodeInputs(inputs).map { it.x to it.y }

    // --- Point (de)serialization ---------------------------------------------------------------
    // Points are the bulk of the DB (a dense page is >100k points), so they're packed as raw
    // binary (25 bytes/point) in a BLOB column — no JSON/Base64 text layer. Two older TEXT formats
    // existed (legacy JSON at ~90-100 bytes/point, then Base64-wrapped binary); both are converted
    // once by MIGRATION_15_16 via [storedTextToBlob]. ByteBuffer is pure-JVM, so unit-testable.

    private const val BYTES_PER_POINT = 25 // tool(1) + x,y,t,pressure,tilt,orientation (6 × 4)

    internal fun encodeInputs(points: List<SerializedStrokeInput>): ByteArray {
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
        return buf.array()
    }

    /**
     * v15→v16 migration helper: converts a stroke's stored TEXT payload — either the legacy JSON
     * array (starts with '[') or the Base64-wrapped binary packing — into the raw BLOB bytes.
     * Both source formats are FROZEN (they only ever appear in pre-v16 rows); don't change this.
     */
    internal fun storedTextToBlob(stored: String): ByteArray = when {
        stored.isEmpty() -> encodeInputs(emptyList())
        stored[0] == '[' -> encodeInputs(json.decodeFromString<List<SerializedStrokeInput>>(stored))
        else -> Base64.getDecoder().decode(stored)
    }

    internal fun decodeInputs(inputs: ByteArray): List<SerializedStrokeInput> {
        if (inputs.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(inputs)
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

    /**
     * The single family ↔ key mapping shared by persistence and brush construction ([BrushSpec]
     * carries these keys; `InkCanvas` builds brushes through [familyFromKey]). The pencil is ink
     * 1.0.0's texture-backed [StockBrushes.pencilUnstable] — "unstable" means the family's look
     * may change across ink versions, which is fine: we persist only our own key string.
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun familyKey(family: BrushFamily): String = when (family) {
        StockBrushes.marker() -> FAMILY_MARKER
        StockBrushes.highlighter() -> FAMILY_HIGHLIGHTER
        StockBrushes.pencilUnstable -> FAMILY_PENCIL
        else -> FAMILY_PRESSURE_PEN
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun familyFromKey(key: String): BrushFamily = when (key) {
        FAMILY_MARKER -> StockBrushes.marker()
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter()
        FAMILY_PENCIL -> StockBrushes.pencilUnstable
        else -> StockBrushes.pressurePen()
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
