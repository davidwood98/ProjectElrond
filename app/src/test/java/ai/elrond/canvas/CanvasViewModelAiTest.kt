package ai.elrond.canvas

import ai.elrond.ai.AiUiState
import ai.elrond.ai.HandwritingRecognizer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelAiTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Treats the whole page as a single handwriting line. */
    private val singleLine: (List<Stroke>) -> List<List<Stroke>> = { listOf(it) }

    /** Deterministic note placement for assertions. */
    private val fixedPlacement: (List<Stroke>) -> ai.elrond.ai.NotePosition =
        { ai.elrond.ai.NotePosition(10f, 20f) }

    private fun viewModel(
        recognizer: HandwritingRecognizer,
        provider: AIProvider?,
        splitter: (List<Stroke>) -> List<List<Stroke>> = singleLine,
    ) = CanvasViewModel(recognizer, provider, splitter, fixedPlacement)

    private class FakeRecognizer(
        private val textFor: (List<Stroke>) -> String,
    ) : HandwritingRecognizer {
        constructor(text: String) : this({ text })

        var callCount = 0
        var warmUpCount = 0

        override suspend fun recognize(strokes: List<Stroke>): Result<String> {
            callCount++
            return Result.success(textFor(strokes))
        }

        override suspend fun warmUp() {
            warmUpCount++
        }
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

    @Test
    fun `inline trigger sends prompt and places response note on the canvas`() = runTest(dispatcher) {
        val provider = FakeProvider("4")
        val viewModel = viewModel(FakeRecognizer("what is 2+2 /Q"), provider)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(listOf("what is 2+2"), provider.prompts)
        assertEquals(AiUiState.Idle, viewModel.aiState.value)
        val note = viewModel.aiNotes.value.single()
        assertEquals("4", note.text)
        assertEquals(10f, note.x)
        assertEquals(20f, note.y)
    }

    @Test
    fun `markdown markers are stripped from response notes`() = runTest(dispatcher) {
        val provider = FakeProvider("about **3,474 km** wide")
        val viewModel = viewModel(FakeRecognizer("how big is moon /Q"), provider)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals("about 3,474 km wide", viewModel.aiNotes.value.single().text)
    }

    @Test
    fun `notes can be moved resized and removed`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hello /Q"), FakeProvider("hi"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val id = viewModel.aiNotes.value.single().id

        viewModel.moveAiNote(id, dx = 5f, dy = -3f)
        assertEquals(15f, viewModel.aiNotes.value.single().x)
        assertEquals(17f, viewModel.aiNotes.value.single().y)

        viewModel.resizeAiNote(id, scaleDelta = 0.5f)
        assertEquals(1.5f, viewModel.aiNotes.value.single().scale)

        viewModel.resizeAiNote(id, scaleDelta = 99f) // clamped
        assertEquals(3f, viewModel.aiNotes.value.single().scale)

        viewModel.removeAiNote(id)
        assertTrue(viewModel.aiNotes.value.isEmpty())
    }

    @Test
    fun `bare trigger uses line above as question and rest as context`() = runTest(dispatcher) {
        val notesStroke = mockk<Stroke>()
        val questionStroke = mockk<Stroke>()
        val triggerStroke = mockk<Stroke>()
        val splitter: (List<Stroke>) -> List<List<Stroke>> =
            { listOf(listOf(notesStroke), listOf(questionStroke), listOf(triggerStroke)) }
        val recognizer = FakeRecognizer { strokes ->
            when (strokes.single()) {
                triggerStroke -> "/Q"
                questionStroke -> "how old is the moon"
                else -> "meeting notes about budget"
            }
        }
        val provider = FakeProvider("4.5 billion years")
        val viewModel = viewModel(recognizer, provider, splitter)

        viewModel.onStrokesFinished(listOf(notesStroke, questionStroke, triggerStroke))
        advanceUntilIdle()

        val sent = provider.prompts.single()
        assertTrue(sent.contains("Handwritten question: how old is the moon"))
        assertTrue(sent.contains("meeting notes about budget"))
        assertEquals("4.5 billion years", viewModel.aiNotes.value.single().text)
        assertEquals(AiUiState.Idle, viewModel.aiState.value)
    }

    @Test
    fun `non-trigger last line does not recognize the rest of the page`() = runTest(dispatcher) {
        val notesStroke = mockk<Stroke>()
        val lastStroke = mockk<Stroke>()
        val splitter: (List<Stroke>) -> List<List<Stroke>> =
            { listOf(listOf(notesStroke), listOf(lastStroke)) }
        val recognizer = FakeRecognizer { strokes ->
            if (strokes.single() === lastStroke) "more notes" else "earlier notes"
        }
        val viewModel = viewModel(recognizer, FakeProvider("unused"), splitter)

        viewModel.onStrokesFinished(listOf(notesStroke, lastStroke))
        advanceUntilIdle()

        // Only the trigger-candidate line was recognized — cheap when no /Q present.
        assertEquals(1, recognizer.callCount)
        assertEquals(AiUiState.Idle, viewModel.aiState.value)
    }

    @Test
    fun `same prompt is not re-sent on subsequent strokes`() = runTest(dispatcher) {
        val provider = FakeProvider("4")
        val viewModel = viewModel(FakeRecognizer("what is 2+2 /Q"), provider)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(1, provider.prompts.size)
    }

    @Test
    fun `missing provider surfaces configuration error`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hello /Q"), provider = null)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertTrue(viewModel.aiState.value is AiUiState.Error)
    }

    @Test
    fun `rapid strokes are debounced into one recognition`() = runTest(dispatcher) {
        val recognizer = FakeRecognizer("no trigger here")
        val viewModel = viewModel(recognizer, FakeProvider("unused"))

        repeat(5) { viewModel.onStrokesFinished(listOf(mockk<Stroke>())) }
        advanceUntilIdle()

        assertEquals(1, recognizer.callCount)
    }

    @Test
    fun `recognizer is warmed up at startup`() = runTest(dispatcher) {
        val recognizer = FakeRecognizer("anything")

        viewModel(recognizer, FakeProvider("unused"))
        advanceUntilIdle()

        assertEquals(1, recognizer.warmUpCount)
    }

    @Test
    fun `clearPage resets assistant state and removes notes`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hello /Q"), FakeProvider("hi"))

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        viewModel.clearPage()

        assertEquals(AiUiState.Idle, viewModel.aiState.value)
        assertTrue(viewModel.finishedStrokes.value.isEmpty())
        assertTrue(viewModel.aiNotes.value.isEmpty())
    }
}
