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
    fun `points survive the compact binary round trip`() {
        val points = listOf(
            SerializedStrokeInput(x = 1.5f, y = 2.25f, t = 0, pressure = 0.8f, tilt = 0.1f, orientation = 1.2f, tool = "stylus"),
            SerializedStrokeInput(x = 3f, y = 4f, t = 16, pressure = 0.9f, tilt = 0.15f, orientation = 1.3f, tool = "touch"),
        )

        val encoded = StrokeSerialization.encodeInputs(points)
        // The compact format is Base64, so it must NOT look like the legacy JSON array.
        assertEquals(false, encoded.startsWith("["))
        assertEquals(points, StrokeSerialization.decodeInputs(encoded))
    }

    @Test
    fun `legacy JSON payloads still decode (back-compat)`() {
        val json = """[{"x":100.0,"y":200.0,"t":0,"pressure":1.0,"tilt":0.0,"orientation":0.0,"tool":"stylus"}]"""

        val decoded = StrokeSerialization.decodeInputs(json)

        assertEquals(1, decoded.size)
        assertEquals(100.0f, decoded.single().x)
        assertEquals("stylus", decoded.single().tool)
    }

    @Test
    fun `compact encoding is far smaller than JSON for a realistic stroke`() {
        val points = (0 until 133).map {
            SerializedStrokeInput(x = it * 1.37f, y = it * 2.11f, t = it * 8L, pressure = 0.7f, tilt = 0.2f, orientation = 0.3f)
        }
        val jsonLen = Json.encodeToString(points).length
        val compactLen = StrokeSerialization.encodeInputs(points).length
        // Expect a big reduction (JSON is ~90-100 bytes/point; compact base64 ~33).
        assertEquals(true, compactLen < jsonLen / 2)
    }
}
