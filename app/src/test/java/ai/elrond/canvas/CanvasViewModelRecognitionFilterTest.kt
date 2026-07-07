package ai.elrond.canvas

import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.domain.NotePosition
import ai.elrond.presentation.CanvasViewModel
import androidx.ink.strokes.Stroke
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FA-23: highlighter strokes never reach recognition — not as the trigger line, not as page
 * context, not in a lasso AI prompt. The [CanvasViewModel] `recognizableInk` seam is faked here;
 * the real predicate (brush-family based) lives in LineRecognition and is device-verified.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelRecognitionFilterTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pen = mockk<Stroke>()
    private val highlight = mockk<Stroke>()

    private class RecordingProvider : AIProvider {
        val prompts = mutableListOf<String>()
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            prompts += (request.input as AIInput.Text).text
            return Result.success(AIResponse("ok", 1, 1, "end_turn"))
        }
    }

    private class FixedRecognizer(private val text: String) : HandwritingRecognizer {
        override suspend fun recognize(strokes: List<Stroke>): Result<String> = Result.success(text)
    }

    @Test
    fun `command trigger detection never sees highlighter strokes`() = runTest(dispatcher) {
        val seenBySplitter = mutableListOf<List<Stroke>>()
        val vm = CanvasViewModel(
            recognizer = FixedRecognizer("hello /Q"),
            aiProvider = RecordingProvider(),
            lineSplitter = { strokes -> seenBySplitter.add(strokes); listOf(strokes) },
            notePlacer = { NotePosition(0f, 0f) },
            recognizableInk = { it !== highlight },
        )

        vm.onStrokesFinished(listOf(pen, highlight))
        advanceUntilIdle()

        assertTrue(seenBySplitter.isNotEmpty())
        seenBySplitter.forEach { strokes ->
            assertFalse("highlighter stroke leaked into recognition", strokes.contains(highlight))
            assertTrue(strokes.contains(pen))
        }
    }

    @Test
    fun `lasso AI prompt reads only the readable strokes in the selection`() = runTest(dispatcher) {
        val seenBySplitter = mutableListOf<List<Stroke>>()
        val provider = RecordingProvider()
        val vm = CanvasViewModel(
            recognizer = FixedRecognizer("circled text"),
            aiProvider = provider,
            lineSplitter = { strokes -> seenBySplitter.add(strokes); listOf(strokes) },
            notePlacer = { NotePosition(0f, 0f) },
            centroidOf = { GestureTriggerDetector.Point(10f, 10f) }, // both inside the lasso
            strokeBoundsOf = { ai.elrond.domain.SelectionBounds(0f, 0f, 10f, 10f) },
            recognizableInk = { it !== highlight },
        )
        vm.onStrokesFinished(listOf(pen, highlight))
        advanceUntilIdle()
        seenBySplitter.clear()
        vm.selectByLasso(
            listOf(
                GestureTriggerDetector.Point(0f, 0f),
                GestureTriggerDetector.Point(100f, 0f),
                GestureTriggerDetector.Point(100f, 100f),
                GestureTriggerDetector.Point(0f, 100f),
            ),
        )
        assertEquals(2, vm.selection.value?.count)

        vm.aiPromptSelection()
        advanceUntilIdle()

        assertEquals(1, provider.prompts.size)
        assertTrue(seenBySplitter.isNotEmpty())
        seenBySplitter.forEach { strokes ->
            assertFalse("highlighter stroke leaked into the AI prompt", strokes.contains(highlight))
        }
    }

    @Test
    fun `a page of only highlighter strokes never queries the AI`() = runTest(dispatcher) {
        val provider = RecordingProvider()
        val vm = CanvasViewModel(
            recognizer = FixedRecognizer("hello /Q"),
            aiProvider = provider,
            lineSplitter = { listOf(it) },
            notePlacer = { NotePosition(0f, 0f) },
            recognizableInk = { false },
        )

        vm.onStrokesFinished(listOf(highlight))
        advanceUntilIdle()

        assertTrue(provider.prompts.isEmpty())
    }
}
