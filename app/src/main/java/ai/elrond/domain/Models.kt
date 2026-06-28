package ai.elrond.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Notebook(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** Per-notebook page-flow override; null = follow the global default (FA-20). */
    val pageNavigationMode: PageNavigationMode? = null,
    /** Per-notebook paper override; null = follow the global default (FA-20). */
    val paperStyle: PaperStyle? = null,
    /** Per-notebook view orientation; null = follow the default (FA-20). */
    val viewOrientation: PageViewOrientation? = null,
    /** Placeholder for a future page-template id (FA-20 deferred). */
    val templateId: String? = null,
    val modifiedAt: Long = createdAt,
)

/** A day on which a note page was edited (one per page per local day). */
data class NoteEditDay(val pageId: String, val date: LocalDate)

data class NotePage(
    val id: String,
    val notebookId: String,
    /** Null means the page uses its auto-generated timestamp title. */
    val customTitle: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    /** When the note was last opened in the editor — drives the "Recent" list / note tabs. */
    val lastOpenedAt: Long = 0,
    val tags: List<String> = emptyList(),
    val contextSummary: String? = null,
    /** FA-20: 1-based position within its notebook (mutable; drag-reorder writes it). */
    val pageNumber: Int = 1,
    /** FA-20: user bookmark flag, surfaced in the page index. */
    val isBookmarked: Boolean = false,
) {
    /** Custom title if set, otherwise a timestamp-based title from the creation time. */
    fun displayTitle(zoneId: ZoneId = ZoneId.systemDefault()): String =
        customTitle ?: TITLE_FORMATTER.format(Instant.ofEpochMilli(createdAt).atZone(zoneId))

    companion object {
        private val TITLE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    }
}
