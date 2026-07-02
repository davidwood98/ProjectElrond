package ai.elrond.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-side round-trip test for the pure-data stroke point format.
 * Full Stroke <-> StrokeEntity conversion needs ink natives and is covered
 * by instrumented tests.
 */
class SerializedStrokeInputTest {

    @Test
    fun `points survive JSON round trip`() {
        val points = listOf(
            SerializedStrokeInput(x = 1.5f, y = 2.25f, t = 0, pressure = 0.8f, tilt = 0.1f, orientation = 1.2f),
            SerializedStrokeInput(x = 3f, y = 4f, t = 16, pressure = 0.9f, tilt = 0.15f, orientation = 1.3f),
        )

        val json = Json.encodeToString(points)
        val decoded = Json.decodeFromString<List<SerializedStrokeInput>>(json)

        assertEquals(points, decoded)
    }

    @Test
    fun `decoding ignores unknown keys for forward compatibility`() {
        val json = """[{"x":1.0,"y":2.0,"t":0,"pressure":0.5,"tilt":0.0,"orientation":0.0,"future":"field"}]"""

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<SerializedStrokeInput>>(json)

        assertEquals(1.0f, decoded.single().x)
    }

    @Test
    fun `points survive the binary blob round trip`() {
        val points = listOf(
            SerializedStrokeInput(x = 1.5f, y = 2.25f, t = 0, pressure = 0.8f, tilt = 0.1f, orientation = 1.2f, tool = "stylus"),
            SerializedStrokeInput(x = 3f, y = 4f, t = 16, pressure = 0.9f, tilt = 0.15f, orientation = 1.3f, tool = "touch"),
        )

        assertEquals(points, StrokeSerialization.decodeInputs(StrokeSerialization.encodeInputs(points)))
    }

    @Test
    fun `empty payloads decode to no points`() {
        assertEquals(emptyList<SerializedStrokeInput>(), StrokeSerialization.decodeInputs(ByteArray(0)))
        assertEquals(
            emptyList<SerializedStrokeInput>(),
            StrokeSerialization.decodeInputs(StrokeSerialization.encodeInputs(emptyList())),
        )
    }

    @Test
    fun `storedTextToBlob converts both pre-v16 TEXT formats losslessly`() {
        val points = listOf(
            SerializedStrokeInput(x = 100f, y = 200f, t = 8, pressure = 1f, tilt = 0f, orientation = 0f, tool = "stylus"),
        )
        val legacyJson = """[{"x":100.0,"y":200.0,"t":8,"pressure":1.0,"tilt":0.0,"orientation":0.0,"tool":"stylus"}]"""
        val base64Compact = java.util.Base64.getEncoder()
            .encodeToString(StrokeSerialization.encodeInputs(points))

        assertEquals(points, StrokeSerialization.decodeInputs(StrokeSerialization.storedTextToBlob(legacyJson)))
        assertEquals(points, StrokeSerialization.decodeInputs(StrokeSerialization.storedTextToBlob(base64Compact)))
        // An empty stored payload becomes a valid empty blob, not a crash.
        assertEquals(emptyList<SerializedStrokeInput>(), StrokeSerialization.decodeInputs(StrokeSerialization.storedTextToBlob("")))
    }

    @Test
    fun `binary encoding is far smaller than the legacy JSON for a realistic stroke`() {
        val points = (0 until 133).map {
            SerializedStrokeInput(x = it * 1.37f, y = it * 2.11f, t = it * 8L, pressure = 0.7f, tilt = 0.2f, orientation = 0.3f)
        }
        val jsonLen = Json.encodeToString(points).length
        val blobLen = StrokeSerialization.encodeInputs(points).size
        // Expect a big reduction (JSON is ~90-100 bytes/point; packed binary is 25).
        assertEquals(true, blobLen < jsonLen / 3)
    }
}
