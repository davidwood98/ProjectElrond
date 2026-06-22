package ai.elrond.data

import ai.elrond.domain.TodoPriority
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoRepositoryTest {

    private val todoDao = mockk<TodoDao>(relaxed = true)
    private var nextId = 0
    private val repository = TodoRepository(
        todoDao = todoDao,
        clock = { FIXED_TIME },
        newId = { "id-${nextId++}" },
    )

    @Test
    fun `addManual stores a non-AI item with trimmed content`() = runTest {
        val slot = slot<TodoItemEntity>()
        coEvery { todoDao.insert(capture(slot)) } returns Unit

        val item = repository.addManual("  Buy milk  ", priority = TodoPriority.MEDIUM)

        assertEquals("Buy milk", slot.captured.title)
        assertEquals(2, slot.captured.priority)
        assertEquals(false, slot.captured.isAiExtracted)
        assertEquals(FIXED_TIME, slot.captured.createdAt)
        assertEquals("Buy milk", item.content)
    }

    @Test
    fun `addExtracted links every item to the source page and flags AI origin`() = runTest {
        val slot = slot<List<TodoItemEntity>>()
        coEvery { todoDao.insertAll(capture(slot)) } returns Unit

        val saved = repository.addExtracted(
            items = listOf(
                TodoRepository.ExtractedTask("Email Sarah", TodoPriority.HIGH, dueAt = 999L),
                TodoRepository.ExtractedTask("Book room", TodoPriority.LOW),
            ),
            sourcePageId = "page-7",
            sourcePageTitle = "Standup notes",
        )

        assertEquals(2, slot.captured.size)
        assertTrue(slot.captured.all { it.isAiExtracted })
        assertTrue(slot.captured.all { it.sourcePageId == "page-7" })
        assertTrue(slot.captured.all { it.sourcePageTitle == "Standup notes" })
        assertEquals(3, slot.captured[0].priority)
        assertEquals(999L, slot.captured[0].dueAt)
        assertEquals(2, saved.size)
    }

    @Test
    fun `addExtracted with no tasks does not touch the dao`() = runTest {
        val result = repository.addExtracted(emptyList(), "page-1", "Note")

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { todoDao.insertAll(any()) }
    }

    @Test
    fun `setCompleted records completion time + status, clears them when reopened`() = runTest {
        // status 2 = DONE, 0 = TODO (TodoStatus ordinals)
        repository.setCompleted("id-1", completed = true)
        coVerify { todoDao.setCompleted("id-1", true, 2, FIXED_TIME) }

        repository.setCompleted("id-1", completed = false)
        coVerify { todoDao.setCompleted("id-1", false, 0, null) }
    }

    @Test
    fun `setStatus syncs the workflow status with the completed flag and time`() = runTest {
        // IN_PROGRESS (1) is not done: completed=false, no completion time.
        repository.setStatus("id-1", ai.elrond.domain.TodoStatus.IN_PROGRESS)
        coVerify { todoDao.setStatus("id-1", 1, false, null) }

        // DONE (2) is completed: completed=true, completion time stamped.
        repository.setStatus("id-1", ai.elrond.domain.TodoStatus.DONE)
        coVerify { todoDao.setStatus("id-1", 2, true, FIXED_TIME) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        repository.delete("id-1")
        coVerify { todoDao.deleteById("id-1") }
    }

    @Test
    fun `editContent updates the existing row preserving other fields`() = runTest {
        val existing = TodoItemEntity(
            id = "id-1",
            title = "old",
            priority = 0,
            isAiExtracted = true,
            sourcePageId = "page-1",
            createdAt = 1L,
        )
        coEvery { todoDao.getById("id-1") } returns existing
        val slot = slot<TodoItemEntity>()
        coEvery { todoDao.update(capture(slot)) } returns Unit

        repository.editContent("id-1", "  new text ", TodoPriority.HIGH, dueAt = 555L)

        assertEquals("new text", slot.captured.title)
        assertEquals(3, slot.captured.priority)
        assertEquals(555L, slot.captured.dueAt)
        assertEquals("page-1", slot.captured.sourcePageId) // preserved
        assertTrue(slot.captured.isAiExtracted) // preserved
    }

    companion object {
        private const val FIXED_TIME = 1_780_000_000_000
    }
}
