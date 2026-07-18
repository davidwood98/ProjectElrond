package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FA-24d Level 1 signal ranking, in isolation (pure — no DB). */
class TagSuggestionEngineTest {

    private fun tag(id: String, name: String = id) = Tag(id = id, name = name, colorArgb = 0)

    private val all = listOf(tag("physics"), tag("maths"), tag("revision"), tag("term2", "term 2"))

    @Test
    fun `same-Subject co-occurrence ranks first, ordered by co-occurrence count`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = emptySet(),
            // physics co-occurs twice, maths once.
            sameSubjectTagIds = listOf("physics", "physics", "maths"),
            linkedTagIds = emptyList(),
            usageCounts = emptyMap(),
            contentWords = emptySet(),
        )
        assertEquals(listOf("physics", "maths"), result.map { it.id })
    }

    @Test
    fun `link-graph candidates follow subject candidates and are de-duped across tiers`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = emptySet(),
            sameSubjectTagIds = listOf("physics"),
            linkedTagIds = listOf("maths", "physics"), // physics already surfaced by subject
            usageCounts = emptyMap(),
            contentWords = emptySet(),
        )
        assertEquals(listOf("physics", "maths"), result.map { it.id })
    }

    @Test
    fun `content-word match surfaces a tag whose name appears as a whole word`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = emptySet(),
            sameSubjectTagIds = emptyList(),
            linkedTagIds = emptyList(),
            usageCounts = emptyMap(),
            contentWords = TagSuggestionEngine.contentWordsOf("todays revision covers waves"),
        )
        assertEquals(listOf("revision"), result.map { it.id })
    }

    @Test
    fun `multi-word tag matches only when all its words are present`() {
        val present = TagSuggestionEngine.suggest(
            all, emptySet(), emptyList(), emptyList(), emptyMap(),
            TagSuggestionEngine.contentWordsOf("notes for term 2 exams"),
        )
        assertTrue("term2" in present.map { it.id })

        // A relevance signal (subject) is present, so frequency-fallback (which would return every
        // tag) does NOT engage — isolating the content signal: "term one" lacks "2", so no match.
        val partial = TagSuggestionEngine.suggest(
            all, emptySet(), sameSubjectTagIds = listOf("physics"), emptyList(), emptyMap(),
            TagSuggestionEngine.contentWordsOf("notes for term one"),
        )
        assertTrue("term2" !in partial.map { it.id })
        assertTrue("physics" in partial.map { it.id })
    }

    @Test
    fun `no relevance signal yields nothing - a blank notebook is not offered the whole registry`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = emptySet(),
            sameSubjectTagIds = emptyList(),
            linkedTagIds = emptyList(),
            usageCounts = mapOf("maths" to 5, "physics" to 2), // frequency is NOT a fallback anymore
            contentWords = emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `assigned tags are never suggested`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = setOf("physics"),
            sameSubjectTagIds = listOf("physics", "maths"),
            linkedTagIds = emptyList(),
            usageCounts = emptyMap(),
            contentWords = emptySet(),
        )
        assertEquals(listOf("maths"), result.map { it.id })
    }

    @Test
    fun `result is capped at the limit`() {
        val result = TagSuggestionEngine.suggest(
            allTags = all,
            assignedTagIds = emptySet(),
            sameSubjectTagIds = listOf("physics", "maths", "revision", "term2"),
            linkedTagIds = emptyList(),
            usageCounts = emptyMap(),
            contentWords = emptySet(),
            limit = 2,
        )
        assertEquals(2, result.size)
    }
}
