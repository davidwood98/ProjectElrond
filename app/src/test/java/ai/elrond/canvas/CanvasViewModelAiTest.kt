package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.presentation.AiUiState
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.data.RecognitionCandidate
import ai.elrond.domain.PrefixTriggerState
import ai.elrond.domain.TriggerMode
import ai.elrond.aibackend.AIException
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
import org.junit.Assert.assertNull
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

    /** Provider that always fails with the given exception (billing/auth fault tests). */
    private class FailingProvider(private val failure: Throwable) : AIProvider {
        override suspend fun generate(request: AIRequest): Result<AIResponse> =
            Result.failure(failure)
    }

    private fun failingViewModel(failure: Throwable) = CanvasViewModel(
        recognizer = FakeRecognizer("hello /Q"),
        aiProvider = FailingProvider(failure),
        lineSplitter = singleLine,
        notePlacer = fixedPlacement,
    )

    @Test
    fun `an out-of-credits API failure shows the billing message, not the connection error`() =
        runTest(dispatcher) {
            // The exact fault from the 2026-07-12 device report: HTTP 400 invalid_request_error
            // whose message names the credit balance. It rendered as "Could not connect" and sent
            // the diagnosis down the network path — it must surface as an actionable billing fault.
            val viewModel = failingViewModel(
                AIException.Api(
                    statusCode = 400,
                    message = "Your credit balance is too low to access the Anthropic API. " +
                        "Please go to Plans & Billing to upgrade or purchase credits.",
                    errorType = "invalid_request_error",
                ),
            )

            viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
            advanceUntilIdle()

            val state = viewModel.aiState.value
            assertTrue(state is AiUiState.Error)
            assertEquals(CanvasViewModel.BILLING_ERROR, (state as AiUiState.Error).message)
        }

    @Test
    fun `a rejected API key shows the auth message`() = runTest(dispatcher) {
        val viewModel = failingViewModel(
            AIException.Api(statusCode = 401, message = "invalid x-api-key", errorType = "authentication_error"),
        )

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val state = viewModel.aiState.value
        assertTrue(state is AiUiState.Error)
        assertEquals(CanvasViewModel.AUTH_ERROR, (state as AiUiState.Error).message)
    }

    @Test
    fun `other API failures keep the generic connection error`() = runTest(dispatcher) {
        val viewModel = failingViewModel(
            AIException.Api(statusCode = 529, message = "Overloaded", errorType = "overloaded_error"),
        )

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val state = viewModel.aiState.value
        assertTrue(state is AiUiState.Error)
        assertEquals(CanvasViewModel.CONNECTION_ERROR, (state as AiUiState.Error).message)
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
    fun `notes can be reflowed and removed`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hello /Q"), FakeProvider("hi"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val id = viewModel.aiNotes.value.single().id

        // Reflow resize (FA-21): width changes, font stays, height clears to wrap.
        val widthBefore = viewModel.aiNotes.value.single().widthPx
        viewModel.reflowAiNoteWidth(id, x = 15f, widthPx = widthBefore + 40f)
        assertEquals(widthBefore + 40f, viewModel.aiNotes.value.single().widthPx)
        assertEquals(15f, viewModel.aiNotes.value.single().x)
        assertNull(viewModel.aiNotes.value.single().heightPx)

        // Width can't shrink below the minimum.
        viewModel.reflowAiNoteWidth(id, x = 15f, widthPx = 10f)
        assertEquals(ai.elrond.domain.AiInkNote.MIN_WIDTH_PX, viewModel.aiNotes.value.single().widthPx)

        viewModel.removeAiNote(id)
        assertTrue(viewModel.aiNotes.value.isEmpty())
    }

    @Test
    fun `an AI box can be selected, moved by a committed transform, and deleted`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hi /Q"), FakeProvider("ok"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val note = viewModel.aiNotes.value.single()

        viewModel.selectAiNote(note.id)
        val sel = viewModel.selection.value
        assertTrue(sel != null && note.id in sel.aiNoteIds && sel.isSingleAiNote)

        // A committed move repositions the box (no canvas size reported → snap-back inert).
        viewModel.previewTransform(ai.elrond.domain.LiveTransform(dx = 20f, dy = 10f))
        viewModel.commitTransform()
        assertEquals(note.x + 20f, viewModel.aiNotes.value.single().x)
        assertEquals(note.y + 10f, viewModel.aiNotes.value.single().y)

        viewModel.deleteSelection()
        assertTrue(viewModel.aiNotes.value.isEmpty())
        assertNull(viewModel.selection.value)
    }

    @Test
    fun `a ratio-locked scale grows the AI box font and width together`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hi /Q"), FakeProvider("ok"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val note = viewModel.aiNotes.value.single()

        viewModel.selectAiNote(note.id)
        viewModel.previewTransform(
            ai.elrond.domain.LiveTransform(scaleX = 2f, scaleY = 2f, pivotX = note.x, pivotY = note.y),
        )
        viewModel.commitTransform()

        val scaled = viewModel.aiNotes.value.single()
        assertEquals(2f, scaled.fontScale)
        assertEquals(note.widthPx * 2f, scaled.widthPx)
    }

    @Test
    fun `deleting a selected AI box is undoable`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hi /Q"), FakeProvider("ok"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val note = viewModel.aiNotes.value.single()

        viewModel.selectAiNote(note.id)
        viewModel.deleteSelection()
        assertTrue(viewModel.aiNotes.value.isEmpty())

        // FA-21: the unified undo snapshot covers AI boxes, so the delete is recoverable.
        assertTrue(viewModel.canUndo.value)
        viewModel.undo()
        assertEquals(listOf(note), viewModel.aiNotes.value)
    }

    @Test
    fun `reporting an AI box measured size hugs the selection bounds`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRecognizer("hi /Q"), FakeProvider("ok"))
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val note = viewModel.aiNotes.value.single()

        viewModel.selectAiNote(note.id)
        viewModel.reportAiNoteMeasuredSize(note.id, widthPx = 120f, heightPx = 40f)

        val b = viewModel.selection.value!!.bounds
        assertEquals(note.x, b.left)
        assertEquals(note.y, b.top)
        assertEquals(note.x + 120f, b.right)
        assertEquals(note.y + 40f, b.bottom)
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
    fun `a re-sent prompt that is still unclear does not offer another guess`() = runTest(dispatcher) {
        // The model keeps replying unclear-with-a-guess; without the loop-breaker this would let the
        // user keep tapping "Yes" forever. After a re-send the new error note carries no guess, so
        // only Edit prompt / Okay remain.
        val provider = FakeProvider("I need more information, request unclear\nDid you mean: how old is the moon?")
        val viewModel = viewModel(FakeRecognizer("how old mn /Q"), provider)
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        val first = viewModel.aiNotes.value.single()
        assertEquals("how old is the moon", first.suggestedQuestion)

        viewModel.resendQuery(first.id, "how old is the moon")
        advanceUntilIdle()

        val second = viewModel.aiNotes.value.single()
        assertTrue("still an error note", second.isError)
        assertNull("a re-sent guess must not offer yet another guess", second.suggestedQuestion)
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

    // ── Prefix `/Q` activation (TriggerMode.PREFIX_COMMAND) ─────────────────────────────────────

    /** Each stroke is its own handwriting line (so the prefix scan sees per-stroke lines). */
    private val perStrokeLines: (List<Stroke>) -> List<List<Stroke>> = { it.map { s -> listOf(s) } }

    private fun prefixViewModel(
        recognizer: HandwritingRecognizer,
        provider: AIProvider?,
    ) = CanvasViewModel(
        recognizer = recognizer,
        aiProvider = provider,
        lineSplitter = perStrokeLines,
        notePlacer = fixedPlacement,
        triggerModeFlow = kotlinx.coroutines.flow.flowOf(TriggerMode.PREFIX_COMMAND),
    )

    /** Recognizer whose recognize + candidates both come from one per-line text mapping. */
    private fun prefixRecognizer(text: (List<Stroke>) -> String): FakeRecognizer =
        FakeRecognizer(text).apply { candidatesFor = { listOf(text(it)) } }

    @Test
    fun `standalone prefix trigger enters the listening state`() = runTest(dispatcher) {
        val q = mockk<Stroke>()
        val vm = prefixViewModel(
            prefixRecognizer { line -> if (line.any { it === q }) "/Q" else "notes" },
            FakeProvider("ok"),
        )

        vm.onStrokesFinished(listOf(q))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Listening)
    }

    @Test
    fun `prompt strokes are tracked and inactivity fires the query`() = runTest(dispatcher) {
        val q = mockk<Stroke>()
        val p = mockk<Stroke>()
        val provider = FakeProvider("4")
        val vm = prefixViewModel(
            prefixRecognizer { line ->
                when {
                    line.any { it === q } -> "/Q"
                    line.any { it === p } -> "what is 2+2"
                    else -> "notes"
                }
            },
            provider,
        )

        vm.onStrokesFinished(listOf(q))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent() // Listening, no prompt yet

        vm.onStrokesFinished(listOf(p))
        runCurrent() // the prompt stroke is appended; the inactivity timer (re)starts
        val listening = vm.prefixTriggerState.value as PrefixTriggerState.Listening
        assertEquals(1, listening.promptStrokeIds.size)

        advanceUntilIdle() // inactivity fires → recognize → submitQuery → back to Idle
        assertEquals(1, provider.prompts.size)
        assertTrue(provider.prompts.single().contains("what is 2+2"))
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Idle)
    }

    @Test
    fun `pen-down during listening holds off the send until writing finishes`() = runTest(dispatcher) {
        val q = mockk<Stroke>()
        val p1 = mockk<Stroke>()
        val p2 = mockk<Stroke>()
        val provider = FakeProvider("answer")
        val vm = prefixViewModel(
            prefixRecognizer { line ->
                when {
                    line.any { it === q } -> "/Q"
                    line.any { it === p1 } -> "what is"
                    line.any { it === p2 } -> "my name"
                    else -> "notes"
                }
            },
            provider,
        )

        vm.onStrokesFinished(listOf(q))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent() // Listening

        vm.onStrokesFinished(listOf(p1))
        runCurrent() // first question stroke; inactivity timer running

        // Pen comes down for the next stroke before the inactivity delay elapses: the send is held.
        vm.onWritingStarted()
        advanceTimeBy(5_000L) // well past the inactivity delay
        runCurrent()
        assertTrue("must not fire while still writing", provider.prompts.isEmpty())
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Listening)

        // Writing finishes (pen up) → timer restarts → fires once idle.
        vm.onStrokesFinished(listOf(p2))
        advanceUntilIdle()
        assertEquals(1, provider.prompts.size)
        assertTrue(provider.prompts.single().contains("what is my name"))
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Idle)
    }

    @Test
    fun `cancel removes the trigger and prompt strokes but not earlier ink`() = runTest(dispatcher) {
        val earlier = mockk<Stroke>()
        val q = mockk<Stroke>()
        val p = mockk<Stroke>()
        val vm = prefixViewModel(
            prefixRecognizer { line ->
                when {
                    line.any { it === q } -> "/Q"
                    line.any { it === p } -> "the question"
                    else -> "earlier notes"
                }
            },
            FakeProvider("ok"),
        )

        // Pre-existing ink that is NOT the trigger: detection finds no standalone /Q, stays Idle.
        vm.onStrokesFinished(listOf(earlier))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Idle)

        // Then the prefix command, then a question stroke.
        vm.onStrokesFinished(listOf(q))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent()
        vm.onStrokesFinished(listOf(p))
        runCurrent()

        vm.cancelPrefixTrigger()

        // Only the pre-/Q ink survives; the trigger + question strokes are gone.
        assertEquals(listOf(earlier), vm.finishedStrokes.value.map { it.stroke })
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Idle)
    }

    @Test
    fun `no-prompt timeout leaves the command on the canvas as ink`() = runTest(dispatcher) {
        val q = mockk<Stroke>()
        val vm = prefixViewModel(
            prefixRecognizer { line -> if (line.any { it === q }) "/Q" else "notes" },
            FakeProvider("ok"),
        )

        vm.onStrokesFinished(listOf(q))
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Listening)

        // No question written before the 2s default timeout → quietly cancel, leaving the /Q ink.
        advanceTimeBy(2_001L)
        runCurrent()

        assertTrue(vm.prefixTriggerState.value is PrefixTriggerState.Idle)
        assertEquals(listOf(q), vm.finishedStrokes.value.map { it.stroke })
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
