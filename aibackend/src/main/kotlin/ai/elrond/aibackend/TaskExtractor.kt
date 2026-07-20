package ai.elrond.aibackend

/**
 * A task/action item the AI found in note content, before it becomes a
 * persisted TODO. Pure data — no Android or app-layer types — so this module
 * stays portable for the future iOS port.
 *
 * @param priority 0 = none, 1 = low, 2 = medium, 3 = high.
 * @param dueDateIso ISO-8601 date (e.g. "2026-06-10") if the note implies one, else null.
 *                   Kept as a string here; the app layer parses it into a timestamp.
 */
data class ExtractedTask(
    val content: String,
    val priority: Int = 0,
    val dueDateIso: String? = null,
)

/**
 * Finds action items, tasks, and commitments in note content.
 *
 * Deliberately independent of the `/Q` trigger and of any app-layer types:
 * the `/Q` flow calls it today, but a future background job (e.g. WorkManager
 * extracting tasks whenever a note is saved) can call the exact same seam with
 * no changes here.
 */
interface TaskExtractor {
    /**
     * @param referenceDate the device's "today" as a human-readable string (e.g.
     *        "Friday 2026-06-05") used to anchor relative dates ("this Monday",
     *        "tomorrow"). Null disables relative-date guidance (back-compat).
     * @param existingTasks the titles already on the to-do list. The model is told to
     *        skip anything that duplicates OR is semantically similar to one of these,
     *        so re-phrasings of an already-captured task aren't re-offered (FA-24e).
     * @return tasks found in [noteContent]; empty list when there are none.
     */
    suspend fun extract(
        noteContent: String,
        referenceDate: String? = null,
        existingTasks: List<String> = emptyList(),
    ): Result<List<ExtractedTask>>
}
