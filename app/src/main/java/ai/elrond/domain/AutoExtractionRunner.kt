package ai.elrond.domain

import ai.elrond.aibackend.CalendarEventExtractor
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.data.CalendarEvent
import ai.elrond.data.CalendarRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One recognized handwriting line and its canvas-pixel bounds (for popup anchoring). */
data class RecognizedLine(
    val text: String,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

/** What the runner did, so the worker can flair the to-do tab / log. */
data class ExtractionOutcome(
    val todosAdded: Int = 0,
    val eventsAdded: Int = 0,
    val todosPending: Int = 0,
    val eventsPending: Int = 0,
) {
    val anyAutoAdded: Boolean get() = todosAdded > 0 || eventsAdded > 0
}

/**
 * Core of FA-2 background auto-extraction, decoupled from Android/WorkManager/ink so it's
 * JVM-testable. Recognizes a page's handwriting, extracts TODO/calendar items (reusing the
 * same `:aibackend` extractors as `/Q`), de-dupes against what already exists, then routes
 * each item: with confirmation on it becomes a [PendingSuggestion] (on-canvas Yes/No popup);
 * with confirmation off it's committed directly (and TODO adds flag the to-do tab).
 *
 * The only ink-touching step — turning a page's strokes into [RecognizedLine]s — is injected
 * as [recognizeLines] so tests can supply canned lines.
 */
class AutoExtractionRunner(
    private val recognizeLines: suspend (pageId: String) -> List<RecognizedLine>,
    private val taskExtractor: TaskExtractor?,
    private val eventExtractor: CalendarEventExtractor?,
    private val todoRepository: TodoRepository,
    private val calendarRepository: CalendarRepository,
    private val suggestionRepository: SuggestionRepository,
    private val resolvePageTitle: suspend (pageId: String) -> String,
    private val markNewTodoItems: suspend () -> Unit,
    /** Last page text a real extraction ran against (FA-24b skip-gate); null = never run. */
    private val loadLastText: suspend (pageId: String) -> String? = { null },
    /** Persist the page text just extracted, so an unchanged next save skips both AI calls. */
    private val saveLastText: suspend (pageId: String, text: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun run(pageId: String, confirmTodo: Boolean, confirmCalendar: Boolean): ExtractionOutcome {
        val lines = recognizeLines(pageId)
        val fullText = lines.joinToString("\n") { it.text }.trim()
        if (fullText.isBlank()) return ExtractionOutcome()

        // Skip-gate (FA-24b): identical page text since the last real run → skip both Anthropic
        // calls. On the first run loadLastText is null, which never equals a non-blank fullText,
        // so a page always extracts at least once.
        if (fullText == loadLastText(pageId)) return ExtractionOutcome()

        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val referenceDate = "${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $today"

        val tasks = taskExtractor?.extract(fullText, referenceDate)?.getOrNull().orEmpty()
        val events = eventExtractor?.extract(fullText, referenceDate)?.getOrNull().orEmpty()
        // Ran the extractors this pass → remember the text so an unchanged next save skips.
        saveLastText(pageId, fullText)
        if (tasks.isEmpty() && events.isEmpty()) return ExtractionOutcome()

        // De-dup against existing todos, existing calendar suggestions, and items already
        // suggested for this page (incl. dismissed — so a rejected item doesn't keep returning).
        // Keys are namespaced by type ("TODO:" / "EVENT:") so a task and an event with the same
        // text don't collide and silently drop one of them.
        val seen = mutableSetOf<String>().apply {
            addAll(runCatching { todoRepository.existingContents() }.getOrDefault(emptySet()).map { "TODO:$it" })
            addAll(runCatching { calendarRepository.suggestedTitles() }.getOrDefault(emptySet()).map { "EVENT:$it" })
            addAll(runCatching { suggestionRepository.existingTypedContents(pageId) }.getOrDefault(emptySet()))
        }

        val pending = mutableListOf<PendingSuggestion>()
        val directTodos = mutableListOf<TodoRepository.ExtractedTask>()
        val directEvents = mutableListOf<CalendarEvent>()
        var itemIndex = 0

        tasks.forEach { task ->
            val normalized = task.content.trim().lowercase()
            val key = "TODO:$normalized"
            if (normalized.isEmpty() || key in seen) return@forEach
            seen += key
            val (x, y) = anchorFor(task.content, lines, itemIndex++)
            val dueAt = task.dueDateIso.toDueMillis(today)
            if (confirmTodo) {
                pending += PendingSuggestion(
                    pageId = pageId, type = SuggestionType.TODO, content = task.content,
                    x = x, y = y, dueAtMillis = dueAt, priority = task.priority.coerceIn(0, 3),
                )
            } else {
                directTodos += TodoRepository.ExtractedTask(
                    content = task.content,
                    priority = TodoPriority.entries[task.priority.coerceIn(0, 3)],
                    dueAt = dueAt,
                )
            }
        }

        events.forEach { event ->
            val normalized = event.title.trim().lowercase()
            val key = "EVENT:$normalized"
            if (normalized.isEmpty() || key in seen) return@forEach
            val start = event.startIso.toDateTimeMillis(today) ?: return@forEach // an event needs a time
            seen += key
            val end = event.endIso.toDateTimeMillis(today)?.takeIf { it > start } ?: (start + ONE_HOUR_MILLIS)
            val (x, y) = anchorFor(event.title, lines, itemIndex++)
            if (confirmCalendar) {
                pending += PendingSuggestion(
                    pageId = pageId, type = SuggestionType.EVENT, content = event.title,
                    x = x, y = y, startMillis = start, endMillis = end, location = event.location,
                )
            } else {
                directEvents += CalendarEvent(
                    title = event.title, description = event.description,
                    startTime = start, endTime = end, location = event.location, isAiSuggested = true,
                )
            }
        }

        suggestionRepository.add(pending)
        if (directTodos.isNotEmpty()) {
            val title = resolvePageTitle(pageId)
            todoRepository.addExtracted(directTodos, sourcePageId = pageId, sourcePageTitle = title)
            markNewTodoItems()
        }
        directEvents.forEach { calendarRepository.addSuggestion(it, sourcePageId = pageId) }

        return ExtractionOutcome(
            todosAdded = directTodos.size,
            eventsAdded = directEvents.size,
            todosPending = pending.count { it.type == SuggestionType.TODO },
            eventsPending = pending.count { it.type == SuggestionType.EVENT },
        )
    }

    /** Anchor the popup just below the line whose text best matches [content]; else a staggered fallback. */
    private fun anchorFor(content: String, lines: List<RecognizedLine>, itemIndex: Int): Pair<Float, Float> {
        val contentTokens = content.lowercase().tokens()
        val best = lines
            .map { it to it.text.lowercase().tokens().count { t -> t in contentTokens } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
        return if (best != null) {
            // Stagger so multiple items matching the same line don't stack exactly on top of each other.
            best.minX to (best.maxY + ANCHOR_OFFSET_Y + itemIndex * ITEM_STAGGER_Y)
        } else {
            LEFT_MARGIN_PX to (FALLBACK_TOP_Y + itemIndex * FALLBACK_STAGGER_Y)
        }
    }

    private fun String.tokens(): Set<String> =
        split(WHITESPACE).map { it.trim() }.filter { it.length > 2 }.toSet()

    /** ISO date ("2026-06-10") or relative phrase ("tomorrow") → start-of-day millis. */
    private fun String?.toDueMillis(today: LocalDate): Long? {
        if (this.isNullOrBlank()) return null
        val date = RelativeDateResolver.resolve(this, today)
            ?: runCatching { LocalDate.parse(this.trim()) }.getOrNull()
            ?: return null
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** ISO date-time ("2026-06-10T15:00"), or a date/relative phrase (→ start of day) → millis. */
    private fun String?.toDateTimeMillis(today: LocalDate): Long? {
        if (this.isNullOrBlank()) return null
        val trimmed = this.trim()
        runCatching { LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        return trimmed.toDueMillis(today)
    }

    private companion object {
        const val LEFT_MARGIN_PX = 32f
        const val ANCHOR_OFFSET_Y = 8f
        const val ITEM_STAGGER_Y = 28f
        const val FALLBACK_TOP_Y = 96f
        const val FALLBACK_STAGGER_Y = 120f
        const val ONE_HOUR_MILLIS = 3_600_000L
        val WHITESPACE = Regex("\\s+")
    }
}
