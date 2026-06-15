package ai.elrond.ai

import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * A single ranked recognition result. Recognizers return these best-first; [score] is the
 * engine's optional confidence (ML Kit may leave it null), so callers should rely on rank.
 */
data class RecognitionCandidate(val text: String, val score: Float? = null)

/** Converts canvas ink strokes to text. Interface so JVM tests can use a fake. */
interface HandwritingRecognizer {
    /** The single best recognition result, or "" when there is no ink. */
    suspend fun recognize(strokes: List<Stroke>): Result<String>

    /**
     * Ranked recognition candidates (best first). Lets callers consider alternatives the
     * top guess missed (e.g. a `/Q` trigger). Default wraps the single [recognize] result,
     * so a fake/implementation that only overrides [recognize] still works.
     */
    suspend fun recognizeCandidates(strokes: List<Stroke>): Result<List<RecognitionCandidate>> =
        recognize(strokes).map { text ->
            if (text.isEmpty()) emptyList() else listOf(RecognitionCandidate(text))
        }

    /**
     * Prepares the recognizer ahead of first use (e.g. downloads the model).
     * Call at app/screen startup so the first /Q doesn't pay the cost.
     */
    suspend fun warmUp() {}

    /** Releases any native recognizer resources. No-op for implementations that hold none. */
    fun close() {}
}

/**
 * ML Kit Digital Ink implementation. Downloads the recognition model on first
 * use (requires network once; cached on-device afterwards).
 */
class MlKitHandwritingRecognizer(
    languageTag: String = "en-US",
) : HandwritingRecognizer {

    private val model: DigitalInkRecognitionModel = DigitalInkRecognitionModel.builder(
        requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)) {
            "Unsupported handwriting language tag: $languageTag"
        },
    ).build()

    private val recognizerLazy = lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }
    private val recognizer get() = recognizerLazy.value

    private val modelManager = RemoteModelManager.getInstance()

    @Volatile
    private var modelReady = false

    override suspend fun recognize(strokes: List<Stroke>): Result<String> =
        recognizeCandidates(strokes).map { it.firstOrNull()?.text.orEmpty() }

    override suspend fun recognizeCandidates(
        strokes: List<Stroke>,
    ): Result<List<RecognitionCandidate>> {
        if (strokes.isEmpty()) return Result.success(emptyList())
        return try {
            ensureModelDownloaded()
            val result = recognizer.recognize(strokes.toMlKitInk()).await()
            Result.success(result.candidates.map { RecognitionCandidate(it.text, it.score) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Best-effort model pre-download; failures are deferred to first recognize(). */
    override suspend fun warmUp() {
        try {
            ensureModelDownloaded()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No network at startup etc. — recognize() retries on first use.
        }
    }

    private suspend fun ensureModelDownloaded() {
        if (modelReady) return
        if (!modelManager.isModelDownloaded(model).await()) {
            modelManager.download(model, DownloadConditions.Builder().build()).await()
        }
        modelReady = true
    }

    /** Releases the native recognizer client, but only if it was ever created. */
    override fun close() {
        if (recognizerLazy.isInitialized()) recognizer.close()
    }
}

private fun List<Stroke>.toMlKitInk(): Ink {
    val inkBuilder = Ink.builder()
    val scratch = StrokeInput()
    forEach { stroke ->
        val strokeBuilder = Ink.Stroke.builder()
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            strokeBuilder.addPoint(Ink.Point.create(scratch.x, scratch.y, scratch.elapsedTimeMillis))
        }
        inkBuilder.addStroke(strokeBuilder.build())
    }
    return inkBuilder.build()
}
