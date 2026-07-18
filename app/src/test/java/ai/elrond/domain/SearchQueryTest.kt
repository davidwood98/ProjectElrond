package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for FA-24c query tokenisation (drives the LIKE content search). */
class SearchQueryTest {

    @Test
    fun `tokenize splits on whitespace and punctuation, dropping empties`() {
        assertEquals(listOf("forward", "kinematics"), SearchQuery.tokenize("forward kinematics"))
        assertEquals(listOf("foo", "bar", "baz"), SearchQuery.tokenize("  foo, \"bar\"  baz-  "))
    }

    @Test
    fun `tokenize keeps digits and drops pure-symbol runs`() {
        assertEquals(listOf("h2o", "test123"), SearchQuery.tokenize("h2o *** test123"))
    }

    @Test
    fun `tokenize is empty for blank or punctuation-only input`() {
        assertTrue(SearchQuery.tokenize("").isEmpty())
        assertTrue(SearchQuery.tokenize("   ").isEmpty())
        assertTrue(SearchQuery.tokenize("--- , . ! ").isEmpty())
    }
}
