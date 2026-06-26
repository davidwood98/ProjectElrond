package ai.elrond.ai

import ai.elrond.domain.QueryTriggerDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTriggerDetectorTest {

    @Test
    fun `extracts prompt before trailing trigger`() {
        assertEquals("What is 2+2?", QueryTriggerDetector.extractPrompt("What is 2+2? /Q"))
    }

    @Test
    fun `accepts lowercase q`() {
        assertEquals("hello", QueryTriggerDetector.extractPrompt("hello /q"))
    }

    @Test
    fun `accepts trigger without surrounding whitespace`() {
        assertEquals("hello", QueryTriggerDetector.extractPrompt("hello/Q"))
    }

    @Test
    fun `accepts whitespace between slash and q`() {
        assertEquals("hello", QueryTriggerDetector.extractPrompt("hello / Q"))
    }

    @Test
    fun `accepts backslash misrecognition of the slash`() {
        assertEquals("hello", QueryTriggerDetector.extractPrompt("""hello \Q"""))
    }

    @Test
    fun `no trigger returns null`() {
        assertNull(QueryTriggerDetector.extractPrompt("just some notes"))
    }

    @Test
    fun `trigger mid-text does not fire`() {
        assertNull(QueryTriggerDetector.extractPrompt("ask /Q something later"))
    }

    @Test
    fun `q at end without slash does not fire`() {
        assertNull(QueryTriggerDetector.extractPrompt("meeting with Q"))
    }

    @Test
    fun `trigger with no preceding content returns null`() {
        assertNull(QueryTriggerDetector.extractPrompt("/Q"))
        assertNull(QueryTriggerDetector.extractPrompt("  /Q  "))
    }

    @Test
    fun `containsTrigger detects bare and inline triggers`() {
        assertTrue(QueryTriggerDetector.containsTrigger("/Q"))
        assertTrue(QueryTriggerDetector.containsTrigger("  /q "))
        assertTrue(QueryTriggerDetector.containsTrigger("hello /Q"))
    }

    @Test
    fun `containsTrigger rejects text without trigger`() {
        assertFalse(QueryTriggerDetector.containsTrigger("just notes"))
        assertFalse(QueryTriggerDetector.containsTrigger("meeting with Q"))
        assertFalse(QueryTriggerDetector.containsTrigger("ask /Q something later"))
    }

    @Test
    fun `custom trigger is matched as a literal at the end`() {
        assertEquals("hello", QueryTriggerDetector.extractPrompt("hello >Q", trigger = ">Q"))
        assertTrue(QueryTriggerDetector.containsTrigger("hello @q", trigger = "@Q"))
        // The default /Q must not fire when a custom trigger is configured.
        assertFalse(QueryTriggerDetector.containsTrigger("hello /Q", trigger = ">Q"))
    }

    @Test
    fun `firstTriggerCandidate prefers the best candidate carrying the trigger`() {
        // Top guess already has the trigger.
        assertEquals(
            "what is 2+2 /Q",
            QueryTriggerDetector.firstTriggerCandidate(listOf("what is 2+2 /Q", "what is 242")),
        )
    }

    @Test
    fun `firstTriggerCandidate recovers a trigger the top guess missed`() {
        // Best guess garbled the slash; a lower-ranked candidate kept it.
        assertEquals(
            "ask the moon /Q",
            QueryTriggerDetector.firstTriggerCandidate(listOf("ask the moon 10", "ask the moon /Q")),
        )
    }

    @Test
    fun `firstTriggerCandidate returns null when no candidate has the trigger`() {
        assertNull(QueryTriggerDetector.firstTriggerCandidate(listOf("just notes", "more notes")))
        assertNull(QueryTriggerDetector.firstTriggerCandidate(emptyList()))
    }

    @Test
    fun `isStandaloneTrigger is true only for the trigger alone`() {
        assertTrue(QueryTriggerDetector.isStandaloneTrigger("/Q"))
        assertTrue(QueryTriggerDetector.isStandaloneTrigger("  /q  "))
        assertTrue(QueryTriggerDetector.isStandaloneTrigger("""\Q"""))
        // A prompt after the trigger (the suffix form is not standalone).
        assertFalse(QueryTriggerDetector.isStandaloneTrigger("/Q why is the sky blue"))
        // A prompt before the trigger (a normal suffix trigger).
        assertFalse(QueryTriggerDetector.isStandaloneTrigger("why is the sky blue /Q"))
        assertFalse(QueryTriggerDetector.isStandaloneTrigger(""))
        assertFalse(QueryTriggerDetector.isStandaloneTrigger("just notes"))
    }

    @Test
    fun `isStandaloneTrigger honours a custom trigger`() {
        assertTrue(QueryTriggerDetector.isStandaloneTrigger(">Q", trigger = ">Q"))
        assertTrue(QueryTriggerDetector.isStandaloneTrigger("  >q ", trigger = ">Q"))
        assertFalse(QueryTriggerDetector.isStandaloneTrigger("ask >Q", trigger = ">Q"))
        // The default /Q must not count as standalone when a custom trigger is configured.
        assertFalse(QueryTriggerDetector.isStandaloneTrigger("/Q", trigger = ">Q"))
    }

    @Test
    fun `firstStandaloneTriggerCandidate recovers a standalone trigger the top guess missed`() {
        assertEquals(
            "/Q",
            QueryTriggerDetector.firstStandaloneTriggerCandidate(listOf("10", "/Q")),
        )
        // A candidate that is a suffix trigger (prompt before it) is not standalone.
        assertNull(QueryTriggerDetector.firstStandaloneTriggerCandidate(listOf("hello /Q", "more")))
    }

    @Test
    fun `firstTriggerCandidate ignores low-confidence candidates past the rank cap`() {
        // The trigger only appears in a deep, low-confidence candidate — must not fire.
        val candidates = listOf("aaa", "bbb", "ccc", "ddd", "eee", "real question /Q")
        assertNull(QueryTriggerDetector.firstTriggerCandidate(candidates, maxCandidates = 5))
        // Raising the cap to include it recovers the trigger.
        assertEquals(
            "real question /Q",
            QueryTriggerDetector.firstTriggerCandidate(candidates, maxCandidates = 6),
        )
    }
}
