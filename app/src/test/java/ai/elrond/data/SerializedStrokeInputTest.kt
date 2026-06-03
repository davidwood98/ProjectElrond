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
}
