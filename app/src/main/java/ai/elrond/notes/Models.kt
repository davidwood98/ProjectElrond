package ai.elrond.notes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Notebook(
    val id: String,
    val name: String,
    val createdAt: Long,
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
    val tags: List<String> = emptyList(),
    val contextSummary: String? = null,
) {
    /** Custom title if set, otherwise a timestamp-based title from the creation time. */
    fun displayTitle(zoneId: ZoneId = ZoneId.systemDefault()): String =
        customTitle ?: TITLE_FORMATTER.format(Instant.ofEpochMilli(createdAt).atZone(zoneId))

    companion object {
        private val TITLE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    }
}
