package ai.elrond.data

import ai.elrond.domain.AutoExtractionRunner
import ai.elrond.aibackend.CalendarEventExtractor
import ai.elrond.aibackend.TaskExtractor
import android.content.Context
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Background TODO/calendar extraction for one note page (FA-2). A [HiltWorker] — its
 * dependencies are injected by the Hilt worker factory (FA-3), replacing the manual lookup
 * from ElrondApplication. Delegates the real work to [AutoExtractionRunner]; honours the
 * auto-extraction setting and battery saver, and retries (with backoff) on transient failure.
 */
@HiltWorker
class ExtractionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val noteRepository: NoteRepository,
    private val todoRepository: TodoRepository,
    private val calendarRepository: CalendarRepository,
    private val suggestionRepository: SuggestionRepository,
    private val taskExtractor: TaskExtractor?,
    private val eventExtractor: CalendarEventExtractor?,
    private val recognizer: HandwritingRecognizer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pageId = inputData.getString(KEY_PAGE_ID) ?: return Result.success()
        if (!settings.autoExtractionEnabled.first()) return Result.success()
        // No API key → AI disabled → nothing to extract (terminal, before the transient gate).
        if (taskExtractor == null && eventExtractor == null) return Result.success()
        // Defer (transiently) while the device is in battery-saver mode.
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) return Result.retry()

        val confirmGlobal = settings.extractionConfirmationEnabled.first()
        val confirmTodo = confirmGlobal && settings.confirmTodoExtraction.first()
        val confirmCalendar = confirmGlobal && settings.confirmCalendarExtraction.first()

        val runner = AutoExtractionRunner(
            recognizeLines = { id ->
                buildRecognizedLines(noteRepository.loadStrokes(id).map { it.stroke }, recognizer)
            },
            taskExtractor = taskExtractor,
            eventExtractor = eventExtractor,
            todoRepository = todoRepository,
            calendarRepository = calendarRepository,
            suggestionRepository = suggestionRepository,
            resolvePageTitle = { id -> noteRepository.getPage(id)?.displayTitle() ?: "Note" },
            markNewTodoItems = { settings.setHasNewExtractedItems(true) },
        )

        return try {
            runner.run(pageId, confirmTodo = confirmTodo, confirmCalendar = confirmCalendar)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline / model download pending / transient API error — retry with backoff.
            Result.retry()
        } finally {
            recognizer.close()
        }
    }

    companion object {
        const val KEY_PAGE_ID = "pageId"

        /** Unique work name per page so rapid saves coalesce into one run. */
        fun uniqueName(pageId: String): String = "auto-extract-$pageId"
    }
}
