package ai.elrond.canvas

import ai.elrond.data.HandwritingRecognizer
import ai.elrond.data.RecognitionCache
import ai.elrond.data.RecognitionCandidate
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import androidx.ink.strokes.Stroke
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FA-24b Stage 2: a COMMAND `/Q` assembles its "other notes on the page" context from the
 * recognition cache, keeping the question/trigger lines on live recognition. Verifies both the
 * cache-hit path (context text comes from the cache, no live recognition of context lines) and
 * the read-only cold-cache fallback to live recognition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSemanticCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fixedPlacement: (List<Stroke>) -> ai.elrond.domain.NotePosition =
        { ai.elrond.domain.NotePosition(10f, 20f) }

    /** Records every stroke handed to live recognition so tests can prove context stayed cached. */
    private class RecordingRecognizer(private val textFor: (List<Stroke>) -> String) : HandwritingRecognizer {
        val recognizedStrokes = mutableSetOf<Stroke>()

        override suspend fun recognize(strokes: List<Stroke>): Result<String> {
            recognizedStrokes.addAll(strokes)
            return Result.success(textFor(strokes))
        }

        override suspend fun recognizeCandidates(
            strokes: List<Stroke>,
        ): Result<List<RecognitionCandidate>> = recognize(strokes).map {
            if (it.isEmpty()) emptyList() else listOf(RecognitionCandidate(it))
        }

        override suspend fun warmUp() = Unit
    }

    private class FakeProvider(private val responseText: String) : AIProvider {
        val prompts = mutableListOf<String>()
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            prompts += (request.input as AIInput.Text).text
            return Result.success(
                AIResponse(text = responseText, inputTokens = 1, outputTokens = 1, stopReason = "end_turn"),
            )
        }
    }

    /** Cache that returns [text] on every lookup (or null to force live fallback), recording calls. */
    private class FakeCache(private val text: String?) : RecognitionCache {
        val lookups = mutableListOf<List<String>>()
        override suspend fun textForLine(pageId: String, orderedStrokeIds: List<String>): String? {
            lookups += orderedStrokeIds
            return text
        }
    }

    // Page = one context line, one question line, one bare-trigger line.
    private val notesStroke = mockk<Stroke>()
    private val questionStroke = mockk<Stroke>()
    private val triggerStroke = mockk<Stroke>()
    private val splitter: (List<Stroke>) -> List<List<Stroke>> =
        { listOf(listOf(notesStroke), listOf(questionStroke), listOf(triggerStroke)) }

    private fun recognizer() = RecordingRecognizer { strokes ->
        when (strokes.single()) {
            triggerStroke -> "/Q"
            questionStroke -> "how old is the moon"
            else -> "LIVE budget notes" // must never reach the prompt when the context is cached
        }
    }

    private fun viewModel(recognizer: HandwritingRecognizer, provider: AIProvider, cache: RecognitionCache) =
        CanvasViewModel(
            recognizer = recognizer,
            aiProvider = provider,
            lineSplitter = splitter,
            notePlacer = fixedPlacement,
            questionLineSelector = { _, triggerIndex -> listOf(triggerIndex - 1) },
            pageId = "page-1",
            recognitionCache = cache,
        )

    @Test
    fun `command trigger builds context from the cache without recognizing context lines`() =
        runTest(dispatcher) {
            val recognizer = recognizer()
            val provider = FakeProvider("4.5 billion years")
            val cache = FakeCache("cached budget notes")
            val viewModel = viewModel(recognizer, provider, cache)

            viewModel.onStrokesFinished(listOf(notesStroke, questionStroke, triggerStroke))
            advanceUntilIdle()

            val sent = provider.prompts.single()
            assertTrue("question stays live", sent.contains("Handwritten question: how old is the moon"))
            assertTrue("context comes from the cache", sent.contains("cached budget notes"))
            assertFalse("context was not re-recognized live", sent.contains("LIVE budget notes"))

            // The question + trigger lines were recognized live; the context line never was.
            assertTrue(triggerStroke in recognizer.recognizedStrokes)
            assertTrue(questionStroke in recognizer.recognizedStrokes)
            assertFalse("context line must not hit the recognizer", notesStroke in recognizer.recognizedStrokes)
            assertTrue("the context line was looked up in the cache", cache.lookups.isNotEmpty())
        }

    @Test
    fun `a cold cache falls back to live recognition for context`() = runTest(dispatcher) {
        val recognizer = recognizer()
        val provider = FakeProvider("4.5 billion years")
        val cache = FakeCache(null) // cold: every lookup misses
        val viewModel = viewModel(recognizer, provider, cache)

        viewModel.onStrokesFinished(listOf(notesStroke, questionStroke, triggerStroke))
        advanceUntilIdle()

        val sent = provider.prompts.single()
        assertTrue("context degrades to live recognition on a miss", sent.contains("LIVE budget notes"))
        assertTrue("the context line hit the recognizer on the miss", notesStroke in recognizer.recognizedStrokes)
        assertTrue("the cache was still consulted first", cache.lookups.isNotEmpty())
    }
}
