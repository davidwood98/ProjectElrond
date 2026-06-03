package ai.elrond.ai

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
}
