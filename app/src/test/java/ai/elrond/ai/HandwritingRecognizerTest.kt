package ai.elrond.ai

import ai.elrond.data.RecognitionCandidate
import ai.elrond.data.HandwritingRecognizer
import androidx.ink.strokes.Stroke
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the [HandwritingRecognizer] interface default that bridges single-result fakes. */
class HandwritingRecognizerTest {

    /** A recognizer that only overrides [recognize] — exercises the default candidate bridge. */
    private class TopOnlyRecognizer(private val text: String) : HandwritingRecognizer {
        override suspend fun recognize(strokes: List<Stroke>): Result<String> = Result.success(text)
    }

    @Test
    fun `default recognizeCandidates wraps the single recognize result`() = runTest {
        val candidates = TopOnlyRecognizer("hello /Q")
            .recognizeCandidates(listOf(mockk<Stroke>()))
            .getOrThrow()
        assertEquals(listOf(RecognitionCandidate("hello /Q")), candidates)
    }

    @Test
    fun `default recognizeCandidates yields nothing for empty recognition`() = runTest {
        val candidates = TopOnlyRecognizer("")
            .recognizeCandidates(listOf(mockk<Stroke>()))
            .getOrThrow()
        assertTrue(candidates.isEmpty())
    }
}
