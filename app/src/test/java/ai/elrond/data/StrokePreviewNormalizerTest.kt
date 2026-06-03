package ai.elrond.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokePreviewNormalizerTest {

    @Test
    fun `empty input produces empty preview`() {
        assertTrue(StrokePreviewNormalizer.normalize(emptyList()).isEmpty())
        assertTrue(StrokePreviewNormalizer.normalize(listOf(emptyList())).isEmpty())
    }

    @Test
    fun `points are normalized into unit range preserving aspect`() {
        val result = StrokePreviewNormalizer.normalize(
            listOf(listOf(100f to 50f, 300f to 150f)), // 200 wide, 100 tall
        )

        // Span is max(200, 100) = 200; both axes divided by the same span.
        assertEquals(listOf(listOf(0f to 0f, 1f to 0.5f)), result)
    }

    @Test
    fun `multiple strokes share the same bounds`() {
        val result = StrokePreviewNormalizer.normalize(
            listOf(
                listOf(0f to 0f, 100f to 0f),
                listOf(0f to 100f, 100f to 100f),
            ),
        )

        assertEquals(
            listOf(
                listOf(0f to 0f, 1f to 0f),
                listOf(0f to 1f, 1f to 1f),
            ),
            result,
        )
    }

    @Test
    fun `long strokes are downsampled but keep their last point`() {
        val longLine = (0..200).map { it.toFloat() to 0f }

        val result = StrokePreviewNormalizer.normalize(listOf(longLine), maxPointsPerStroke = 10)

        val line = result.single()
        assertTrue(line.size <= 42) // ceil(201/10)=21 stride → ~10 points + last
        assertEquals(1f to 0f, line.last())
    }

    @Test
    fun `degenerate single point does not divide by zero`() {
        val result = StrokePreviewNormalizer.normalize(listOf(listOf(50f to 50f)))

        assertEquals(listOf(listOf(0f to 0f)), result)
    }
}
