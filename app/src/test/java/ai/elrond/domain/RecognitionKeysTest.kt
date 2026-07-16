package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure key/diff logic backing the FA-24b recognition cache. */
class RecognitionKeysTest {

    @Test
    fun `key is deterministic for the same page and ordered ids`() {
        assertEquals(
            recognizedLineKey("p1", listOf("a", "b", "c")),
            recognizedLineKey("p1", listOf("a", "b", "c")),
        )
    }

    @Test
    fun `key is a 64-char lowercase sha-256 hex string`() {
        val key = recognizedLineKey("p1", listOf("a", "b"))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `stroke order changes the key`() {
        assertNotEquals(
            recognizedLineKey("p1", listOf("a", "b")),
            recognizedLineKey("p1", listOf("b", "a")),
        )
    }

    @Test
    fun `adding or removing a stroke changes the key (invalidation)`() {
        val base = recognizedLineKey("p1", listOf("a", "b"))
        assertNotEquals(base, recognizedLineKey("p1", listOf("a", "b", "c")))
        assertNotEquals(base, recognizedLineKey("p1", listOf("a")))
    }

    @Test
    fun `the same ids on a different page yield a different key`() {
        assertNotEquals(
            recognizedLineKey("p1", listOf("a", "b")),
            recognizedLineKey("p2", listOf("a", "b")),
        )
    }

    @Test
    fun `diff splits current keys into new and stale`() {
        val (newKeys, staleKeys) = recognitionCacheDiff(
            currentKeys = setOf("a", "b", "c"),
            cachedKeys = setOf("b", "c", "d"),
        )
        assertEquals(setOf("a"), newKeys)
        assertEquals(setOf("d"), staleKeys)
    }

    @Test
    fun `diff with identical sets is empty on both sides`() {
        val (newKeys, staleKeys) = recognitionCacheDiff(setOf("a", "b"), setOf("a", "b"))
        assertTrue(newKeys.isEmpty())
        assertTrue(staleKeys.isEmpty())
    }

    @Test
    fun `first run with an empty cache treats every current key as new`() {
        val (newKeys, staleKeys) = recognitionCacheDiff(setOf("a", "b"), emptySet())
        assertEquals(setOf("a", "b"), newKeys)
        assertTrue(staleKeys.isEmpty())
    }
}
