package ai.elrond.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagMatchingTest {

    @Test
    fun `exact and case-insensitive matches are near-duplicates`() {
        assertTrue(TagMatching.isNearDuplicate("Physics", "physics"))
    }

    @Test
    fun `singular and plural collapse`() {
        assertTrue(TagMatching.isNearDuplicate("revision", "revisions"))
    }

    @Test
    fun `word-subset collapses in either direction`() {
        assertTrue(TagMatching.isNearDuplicate("settings", "user settings"))
        assertTrue(TagMatching.isNearDuplicate("user settings", "settings"))
    }

    @Test
    fun `distinct topics are not near-duplicates`() {
        assertFalse(TagMatching.isNearDuplicate("physics", "chemistry"))
        assertFalse(TagMatching.isNearDuplicate("maths", "mathematics")) // not a substring/plural
    }

    @Test
    fun `blank names never match`() {
        assertFalse(TagMatching.isNearDuplicate("", "physics"))
    }

    @Test
    fun `nearDuplicateOfAny scans the collection`() {
        assertTrue(TagMatching.nearDuplicateOfAny("revisions", listOf("physics", "revision")))
        assertFalse(TagMatching.nearDuplicateOfAny("biology", listOf("physics", "revision")))
    }
}
