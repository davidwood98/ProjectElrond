package ai.elrond.ai

import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Converts canvas ink strokes to text. Interface so JVM tests can use a fake. */
interface HandwritingRecognizer {
    suspend fun recognize(strokes: List<Stroke>): Result<String>

    /**
     * Prepares the recognizer ahead of first use (e.g. downloads the model).
     * Call at app/screen startup so the first /Q doesn't pay the cost.
     */
    suspend fun warmUp() {}
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

    private val recognizer by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    private val modelManager = RemoteModelManager.getInstance()

    @Volatile
    private var modelReady = false

    override suspend fun recognize(strokes: List<Stroke>): Result<String> {
        if (strokes.isEmpty()) return Result.success("")
        return try {
            ensureModelDownloaded()
            val result = recognizer.recognize(strokes.toMlKitInk()).await()
            Result.success(result.candidates.firstOrNull()?.text.orEmpty())
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
