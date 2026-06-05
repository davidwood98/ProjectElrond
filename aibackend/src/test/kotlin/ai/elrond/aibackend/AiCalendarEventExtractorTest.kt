package ai.elrond.aibackend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCalendarEventExtractorTest {

    private fun providerReturning(text: String) = object : AIProvider {
        var called = false
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            called = true
            return Result.success(AIResponse(text, 1, 1, "end_turn"))
        }
    }

    @Test
    fun `parses events with time, location and attendees`() = runTest {
        val provider = providerReturning(
            """[{"title":"Meeting with John","start":"2026-06-12T15:00","end":"2026-06-12T16:00",
                "location":"Room 2","attendees":["John"],"description":"Q3 review"}]""",
        )
        val events = AiCalendarEventExtractor(provider).extract("notes").getOrThrow()

        assertEquals(1, events.size)
        assertEquals("Meeting with John", events[0].title)
        assertEquals("2026-06-12T15:00", events[0].startIso)
        assertEquals("Room 2", events[0].location)
        assertEquals(listOf("John"), events[0].attendees)
    }

    @Test
    fun `tolerates prose and code fences around the json`() = runTest {
        val provider = providerReturning(
            "Sure!\n```json\n[{\"title\":\"Dentist\",\"start\":\"2026-07-01T09:00\"}]\n```",
        )
        val events = AiCalendarEventExtractor(provider).extract("notes").getOrThrow()
        assertEquals(listOf("Dentist"), events.map { it.title })
    }

    @Test
    fun `empty array and blank input yield no events`() = runTest {
        assertTrue(AiCalendarEventExtractor(providerReturning("[]")).extract("notes").getOrThrow().isEmpty())

        val provider = providerReturning("[]")
        assertTrue(AiCalendarEventExtractor(provider).extract("   ").getOrThrow().isEmpty())
        assertTrue(!provider.called) // short-circuits on blank
    }

    @Test
    fun `titleless entries are dropped`() = runTest {
        val provider = providerReturning("""[{"start":"2026-06-12T15:00"},{"title":"Lunch"}]""")
        val events = AiCalendarEventExtractor(provider).extract("notes").getOrThrow()
        assertEquals(listOf("Lunch"), events.map { it.title })
    }

    @Test
    fun `non-json response yields empty list`() = runTest {
        val events = AiCalendarEventExtractor(providerReturning("No events found."))
            .extract("notes").getOrThrow()
        assertTrue(events.isEmpty())
    }
}
