package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.presentation.AiUiState
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.ai.RecognitionCandidate
import ai.elrond.domain.TriggerMode
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import androidx.ink.strokes.Stroke
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    private val fixedPlacement: (List<Stroke>) -> ai.elrond.domain.NotePosition =
        { ai.elrond.domain.NotePosition(10f, 20f) }

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

        /** When set, drives [recognizeCandidates] directly (else it mirrors the interface default). */
        var candidatesFor: ((List<Stroke>) -> List<String>)? = null

        override suspend fun recognize(strokes: List<Stroke>): Result<String> {
            callCount++
            return Result.success(textFor(strokes))
        }

        override suspend fun recognizeCandidates(
            strokes: List<Stroke>,
        ): Result<List<RecognitionCandidate>> {
            val provider = candidatesFor
            if (provider == null) {
                // Mirror the interface default: a single candidate from recognize().
                return recognize(strokes).map {
                    if (it.isEmpty()) emptyList() else listOf(RecognitionCandidate(it))
                }
            }
            callCount++
            return Result.success(provider(strokes).map { RecognitionCandidate(it) })
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

    /** Provider that never returns within the timeout window. */
    private class HangingProvider : AIProvider {
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }

    @Test
    fun `request that exceeds the timeout shows the connection error`() = runTest(dispatcher) {
        val viewModel = CanvasViewModel(
            recognizer = FakeRecognizer("hello /Q"),
            aiProvider = HangingProvider(),
            lineSplitter = singleLine,
            notePlacer = fixedPlacement,
            requestTimeoutMillis = 100L,
        )

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle() // virtual time advances past the 100ms timeout

        val state = viewModel.aiState.value
        assertTrue(state is AiUiState.Error)
        assertEquals(CanvasViewModel.CONNECTION_ERROR, (state as AiUiState.Error).message)
    }

    @Test
    fun `loading state is shown at the trigger position while thinking`() = runTest(dispatcher) {
        val viewModel = CanvasViewModel(
            recognizer = FakeRecognizer("hello /Q"),
            aiProvider = HangingProvider(),
            lineSplitter = singleLine,
            notePlacer = fixedPlacement,
        )

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent() // reach provider.generate (suspended), state is Thinking

        val state = viewModel.aiState.value
        assertTrue(state is AiUiState.Thinking)
        assertEquals(10f, (state as AiUiState.Thinking).x)
        assertEquals(20f, state.y)
    }

    @Test
    fun `configured trigger is used instead of the default`() = runTest(dispatcher) {
        val provider = FakeProvider("ok")
        val viewModel = CanvasViewModel(
            recognizer = FakeRecognizer("what is 2+2 >Q"),
            aiProvider = provider,
            lineSplitter = singleLine,
            notePlacer = fixedPlacement,
            triggerCommandFlow = kotlinx.coroutines.flow.flowOf(">Q"),
        )

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(listOf("what is 2+2"), provider.prompts)
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

        // Free resize: width and height move independently (aspect unlocked).
        val widthBefore = viewModel.aiNotes.value.single().widthPx
        viewModel.resizeAiNote(id, dWidth = 40f, dHeight = 25f)
        assertEquals(widthBefore + 40f, viewModel.aiNotes.value.single().widthPx)
        assertEquals(ai.elrond.domain.AiInkNote.MIN_HEIGHT_PX + 25f, viewModel.aiNotes.value.single().heightPx)

        // Width can't shrink below the minimum.
        viewModel.resizeAiNote(id, dWidth = -100000f, dHeight = 0f)
        assertEquals(ai.elrond.domain.AiInkNote.MIN_WIDTH_PX, viewModel.aiNotes.value.single().widthPx)

        viewModel.removeAiNote(id)
        assertTrue(viewModel.aiNotes.value.isEmpty())
    }

    @Test
    fun `an unclear response renders as an error note carrying the recognized question`() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeRecognizer("what /Q"),
            FakeProvider("I need more information, request unclear"),
        )
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val note = viewModel.aiNotes.value.single()
        assertTrue("unclear answers must be flagged as error notes", note.isError)
        assertEquals("what", note.sourceQuestion)
    }

    @Test
    fun `an unclear response with a guess offers a Did-you-mean clarification`() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeRecognizer("how old mn /Q"),
            FakeProvider("I need more information, request unclear\nDid you mean: how old is the moon?"),
        )
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val note = viewModel.aiNotes.value.single()
        assertTrue("unclear answers are error notes", note.isError)
        // The "Did you mean" line drives the Yes/No clarifier and is stripped from the body.
        assertEquals("how old is the moon", note.suggestedQuestion)
        assertEquals("I need more information, request unclear", note.text)
    }

    @Test
    fun `re-lassoing the same selection triggers a fresh answer`() = runTest(dispatcher) {
        val contentStroke = mockk<Stroke>()
        val lassoStroke = mockk<Stroke>()
        val polygon = listOf(
            GestureTriggerDetector.Point(0f, 0f),
            GestureTriggerDetector.Point(100f, 0f),
            GestureTriggerDetector.Point(100f, 100f),
            GestureTriggerDetector.Point(0f, 100f),
        )
        val provider = FakeProvider("Paris")
        val viewModel = CanvasViewModel(
            recognizer = FakeRecognizer { "what is the capital of France" },
            aiProvider = provider,
            lineSplitter = singleLine,
            notePlacer = fixedPlacement,
            lassoOf = { stroke -> polygon.takeIf { stroke === lassoStroke } },
            centroidOf = { GestureTriggerDetector.Point(50f, 50f) }, // inside the polygon
            triggerModeFlow = kotlinx.coroutines.flow.flowOf(TriggerMode.GESTURE),
        )

        viewModel.onStrokesFinished(listOf(contentStroke, lassoStroke))
        advanceUntilIdle()
        // The lasso was consumed; re-draw it over the same content to re-select.
        viewModel.onStrokesFinished(listOf(lassoStroke))
        advanceUntilIdle()

        // A deliberate gesture bypasses the unchanged-content de-dupe and fires both times.
        assertEquals(2, provider.prompts.size)
    }

    @Test
    fun `resending an edited prompt drops the error note and submits the new text`() = runTest(dispatcher) {
        val provider = FakeProvider("I need more information, request unclear")
        val viewModel = viewModel(FakeRecognizer("what /Q"), provider)
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val errorId = viewModel.aiNotes.value.single().id

        viewModel.resendQuery(errorId, "what is the capital of France")
        advanceUntilIdle()

        assertTrue("the original error note is dismissed", viewModel.aiNotes.value.none { it.id == errorId })
        assertTrue("the edited prompt is sent", provider.prompts.any { it.contains("capital of France") })
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
        // Inject a single-line-above selector (the production span selector needs real ink).
        val viewModel = CanvasViewModel(
            recognizer = recognizer,
            aiProvider = provider,
            lineSplitter = splitter,
            notePlacer = fixedPlacement,
            questionLineSelector = { _, triggerIndex -> listOf(triggerIndex - 1) },
        )

        viewModel.onStrokesFinished(listOf(notesStroke, questionStroke, triggerStroke))
        advanceUntilIdle()

        val sent = provider.prompts.single()
        assertTrue(sent.contains("Handwritten question: how old is the moon"))
        assertTrue(sent.contains("meeting notes about budget"))
        assertEquals("4.5 billion years", viewModel.aiNotes.value.single().text)
        assertEquals(AiUiState.Idle, viewModel.aiState.value)
    }

    @Test
    fun `bare trigger gathers a multi-line block above as one question`() = runTest(dispatcher) {
        val ctxStroke = mockk<Stroke>()
        val q1Stroke = mockk<Stroke>()
        val q2Stroke = mockk<Stroke>()
        val triggerStroke = mockk<Stroke>()
        val splitter: (List<Stroke>) -> List<List<Stroke>> = {
            listOf(listOf(ctxStroke), listOf(q1Stroke), listOf(q2Stroke), listOf(triggerStroke))
        }
        val recognizer = FakeRecognizer { strokes ->
            when (strokes.single()) {
                triggerStroke -> "/Q"
                q1Stroke -> "how do I"
                q2Stroke -> "make sourdough bread"
                else -> "shopping list"
            }
        }
        val provider = FakeProvider("Mix flour, water and starter…")
        val viewModel = CanvasViewModel(
            recognizer = recognizer,
            aiProvider = provider,
            lineSplitter = splitter,
            notePlacer = fixedPlacement,
            // Lines 1 and 2 are the contiguous question block above the trigger (line 3).
            questionLineSelector = { _, _ -> listOf(1, 2) },
        )

        viewModel.onStrokesFinished(listOf(ctxStroke, q1Stroke, q2Stroke, triggerStroke))
        advanceUntilIdle()

        val sent = provider.prompts.single()
        assertTrue(sent.contains("Handwritten question: how do I make sourdough bread"))
        assertTrue(sent.contains("shopping list"))
    }

    @Test
    fun `lasso gesture asks about enclosed strokes and removes the lasso`() = runTest(dispatcher) {
        val contentStroke = mockk<Stroke>()
        val lassoStroke = mockk<Stroke>()
        val polygon = listOf(
            GestureTriggerDetector.Point(0f, 0f),
            GestureTriggerDetector.Point(100f, 0f),
            GestureTriggerDetector.Point(100f, 100f),
            GestureTriggerDetector.Point(0f, 100f),
        )
        val provider = FakeProvider("Paris")
        val viewModel = CanvasViewModel(
            recognizer = FakeRecognizer { "what is the capital of France" },
            aiProvider = provider,
            lineSplitter = singleLine,
            notePlacer = fixedPlacement,
            lassoOf = { stroke -> polygon.takeIf { stroke === lassoStroke } },
            centroidOf = { GestureTriggerDetector.Point(50f, 50f) }, // inside the polygon
            triggerModeFlow = kotlinx.coroutines.flow.flowOf(TriggerMode.GESTURE),
        )

        viewModel.onStrokesFinished(listOf(contentStroke, lassoStroke))
        advanceUntilIdle()

        assertEquals(listOf("what is the capital of France"), provider.prompts)
        assertEquals("Paris", viewModel.aiNotes.value.single().text)
        // The lasso was a gesture, not ink — only the content stroke remains.
        assertEquals(listOf(contentStroke), viewModel.finishedStrokes.value.map { it.stroke })
    }

    @Test
    fun `trigger is recovered from a lower-ranked recognition candidate`() = runTest(dispatcher) {
        val provider = FakeProvider("4")
        val recognizer = FakeRecognizer { "what is 2+2 10" }.apply {
            // Best guess garbled the slash; a lower-ranked candidate kept it.
            candidatesFor = { listOf("what is 2+2 10", "what is 2+2 /Q") }
        }
        val viewModel = viewModel(recognizer, provider)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(listOf("what is 2+2"), provider.prompts)
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
    fun `failed request surfaces the connection error on the canvas`() = runTest(dispatcher) {
        val failing = object : AIProvider {
            override suspend fun generate(request: AIRequest): Result<AIResponse> =
                Result.failure(ai.elrond.aibackend.AIException.Network(RuntimeException("offline")))
        }
        val viewModel = viewModel(FakeRecognizer("hello /Q"), failing)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val state = viewModel.aiState.value
        assertTrue(state is AiUiState.Error)
        assertEquals(CanvasViewModel.CONNECTION_ERROR, (state as AiUiState.Error).message)
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
