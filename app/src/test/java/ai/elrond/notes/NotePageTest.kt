package ai.elrond.notes

import ai.elrond.domain.NotePage
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class NotePageTest {

    private fun page(customTitle: String? = null) = NotePage(
        id = "page-1",
        notebookId = "nb-1",
        customTitle = customTitle,
        createdAt = 1_780_000_000_000, // 2026-05-28 20:26:40 UTC
        modifiedAt = 1_780_000_000_000,
    )

    @Test
    fun `custom title wins when set`() {
        assertEquals("Team standup", page(customTitle = "Team standup").displayTitle())
    }

    @Test
    fun `falls back to timestamp title from creation time`() {
        assertEquals("28 May 2026, 20:26", page().displayTitle(ZoneOffset.UTC))
    }
}
