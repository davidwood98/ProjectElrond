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
    fun `word-subset collapses in either direction for isNearDuplicate`() {
        assertTrue(TagMatching.isNearDuplicate("settings", "user settings"))
        assertTrue(TagMatching.isNearDuplicate("user settings", "settings"))
    }

    @Test
    fun `isSameTag tolerates case and plural but NOT word-subset`() {
        assertTrue(TagMatching.isSameTag("Physics", "physics"))
        assertTrue(TagMatching.isSameTag("revision", "revisions"))
        // A more-specific tag is a DIFFERENT tag under isSameTag (so "spider graph" survives "graph").
        assertFalse(TagMatching.isSameTag("graph", "spider graph"))
        assertFalse(TagMatching.isSameTag("settings", "user settings"))
    }

    @Test
    fun `sameTagAsAny scans by exact-or-plural only`() {
        assertTrue(TagMatching.sameTagAsAny("revisions", listOf("physics", "revision")))
        assertFalse(TagMatching.sameTagAsAny("spider graph", listOf("graph", "physics")))
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
