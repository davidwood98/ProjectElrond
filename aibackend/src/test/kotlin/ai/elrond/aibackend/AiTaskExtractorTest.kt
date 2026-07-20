package ai.elrond.aibackend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTaskExtractorTest {

    private fun providerReturning(text: String) = object : AIProvider {
        var lastRequest: AIRequest? = null
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            lastRequest = request
            return Result.success(AIResponse(text, inputTokens = 1, outputTokens = 1, stopReason = "end_turn"))
        }
    }

    @Test
    fun `parses a clean json array of tasks`() = runTest {
        val provider = providerReturning(
            """[{"content":"Email Sarah the report","priority":3,"dueDate":"2026-06-10"},
                {"content":"Book meeting room","priority":1,"dueDate":null}]""",
        )
        val tasks = AiTaskExtractor(provider).extract("notes").getOrThrow()

        assertEquals(2, tasks.size)
        assertEquals("Email Sarah the report", tasks[0].content)
        assertEquals(3, tasks[0].priority)
        assertEquals("2026-06-10", tasks[0].dueDateIso)
        assertEquals(null, tasks[1].dueDateIso)
    }

    @Test
    fun `tolerates code fences and surrounding prose`() = runTest {
        val provider = providerReturning(
            "Here are the tasks:\n```json\n[{\"content\":\"Call the bank\",\"priority\":2}]\n```",
        )
        val tasks = AiTaskExtractor(provider).extract("notes").getOrThrow()

        assertEquals(listOf("Call the bank"), tasks.map { it.content })
        assertEquals(2, tasks.single().priority)
    }

    @Test
    fun `empty array yields no tasks`() = runTest {
        val tasks = AiTaskExtractor(providerReturning("[]")).extract("just facts").getOrThrow()
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `blank note content short-circuits without calling the provider`() = runTest {
        val provider = providerReturning("[]")
        val tasks = AiTaskExtractor(provider).extract("   ").getOrThrow()

        assertTrue(tasks.isEmpty())
        assertEquals(null, provider.lastRequest) // never called
    }

    @Test
    fun `priority is clamped and blank content dropped`() = runTest {
        val provider = providerReturning(
            """[{"content":"  ","priority":1},{"content":"Do thing","priority":9}]""",
        )
        val tasks = AiTaskExtractor(provider).extract("notes").getOrThrow()

        assertEquals(listOf("Do thing"), tasks.map { it.content })
        assertEquals(3, tasks.single().priority) // 9 clamped to max 3
    }

    @Test
    fun `non-json response yields empty list`() = runTest {
        val tasks = AiTaskExtractor(providerReturning("I could not find any tasks."))
            .extract("notes").getOrThrow()
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `reference date is delivered to the model in the prompt`() = runTest {
        val provider = providerReturning("[]")
        AiTaskExtractor(provider).extract("Collect package this Monday", referenceDate = "Friday 2026-06-05")

        val prompt = (provider.lastRequest!!.input as AIInput.Text).text
        assertTrue(prompt.contains("2026-06-05"))
        assertTrue(prompt.contains("Friday"))
    }

    @Test
    fun `existing tasks are delivered to the model for de-dup`() = runTest {
        val provider = providerReturning("[]")
        AiTaskExtractor(provider).extract(
            "notes",
            existingTasks = listOf("Email Sarah the report", "  ", "Book meeting room"),
        )

        val prompt = (provider.lastRequest!!.input as AIInput.Text).text
        assertTrue(prompt.contains("ALREADY ON LIST"))
        assertTrue(prompt.contains("Email Sarah the report"))
        assertTrue(prompt.contains("Book meeting room"))
    }

    @Test
    fun `no existing tasks keeps the prompt free of the already-on-list block`() = runTest {
        val provider = providerReturning("[]")
        AiTaskExtractor(provider).extract("notes", existingTasks = listOf("   ", ""))

        val prompt = (provider.lastRequest!!.input as AIInput.Text).text
        assertFalse(prompt.contains("ALREADY ON LIST"))
    }

    @Test
    fun `no reference date keeps the prompt free of a today anchor`() = runTest {
        val provider = providerReturning("[]")
        AiTaskExtractor(provider).extract("notes")

        val prompt = (provider.lastRequest!!.input as AIInput.Text).text
        assertFalse(prompt.contains("Today is"))
    }

    @Test
    fun `provider failure propagates as failure`() = runTest {
        val failing = object : AIProvider {
            override suspend fun generate(request: AIRequest): Result<AIResponse> =
                Result.failure(AIException.Network(RuntimeException("offline")))
        }
        val result = AiTaskExtractor(failing).extract("notes")
        assertTrue(result.isFailure)
    }
}
