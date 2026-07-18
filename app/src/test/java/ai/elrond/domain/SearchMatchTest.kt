package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the FA-24c three-source ranked merge (title / content / tag / partial-title tiers). */
class SearchMatchTest {

    @Test
    fun `only notebooks in scope appear`() {
        val ranked = SearchMatch.rankedNotebookIds(
            scopeIds = setOf("a", "b"),
            titleFull = setOf("c"), // out of scope
            titlePartial = emptySet(),
            tagMatches = setOf("b"),
            contentRelevance = mapOf("a" to 1.0),
        )
        assertEquals(listOf("a", "b"), ranked)
    }

    @Test
    fun `full-title beats content beats tag beats partial-title`() {
        val ranked = SearchMatch.rankedNotebookIds(
            scopeIds = setOf("full", "content", "tag", "partial"),
            titleFull = setOf("full"),
            titlePartial = setOf("partial"),
            tagMatches = setOf("tag"),
            contentRelevance = mapOf("content" to 0.5),
        )
        assertEquals(listOf("full", "content", "tag", "partial"), ranked)
    }

    @Test
    fun `within content, higher relevance ranks first`() {
        val ranked = SearchMatch.rankedNotebookIds(
            scopeIds = setOf("weak", "strong"),
            titleFull = emptySet(),
            titlePartial = emptySet(),
            tagMatches = emptySet(),
            contentRelevance = mapOf("weak" to 0.1, "strong" to 9.0),
        )
        assertEquals(listOf("strong", "weak"), ranked)
    }

    @Test
    fun `a notebook takes its best source`() {
        val ranked = SearchMatch.rankedNotebookIds(
            scopeIds = setOf("x", "y"),
            titleFull = setOf("x"),
            titlePartial = emptySet(),
            tagMatches = emptySet(),
            contentRelevance = mapOf("x" to 0.1, "y" to 100.0),
        )
        assertEquals(listOf("x", "y"), ranked)
    }

    @Test
    fun `ties break by natural order then id`() {
        val ranked = SearchMatch.rankedNotebookIds(
            scopeIds = setOf("a", "b", "c"),
            titleFull = emptySet(),
            titlePartial = emptySet(),
            tagMatches = setOf("a", "b", "c"),
            contentRelevance = emptyMap(),
            naturalOrder = listOf("c", "a"),
        )
        assertEquals(listOf("c", "a", "b"), ranked)
    }
}
