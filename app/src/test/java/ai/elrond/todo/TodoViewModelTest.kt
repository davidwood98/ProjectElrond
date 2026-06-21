package ai.elrond.todo

import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoItem
import ai.elrond.presentation.TodoViewModel
import ai.elrond.data.TodoRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TodoRepository>(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun item(id: String) = TodoItem(id = id, content = "task $id", createdAt = 1L)

    @Test
    fun `items mirrors the repository list`() = runTest(dispatcher) {
        every { repository.observeAll() } returns flowOf(listOf(item("a"), item("b")))
        every { repository.observeActiveCount() } returns flowOf(2)
        val viewModel = TodoViewModel(repository)

        backgroundScope.launch { viewModel.items.collect { } }
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), viewModel.items.value.map { it.id })
    }

    @Test
    fun `activeCount mirrors the repository badge count`() = runTest(dispatcher) {
        every { repository.observeAll() } returns flowOf(emptyList())
        every { repository.observeActiveCount() } returns flowOf(5)
        val viewModel = TodoViewModel(repository)

        backgroundScope.launch { viewModel.activeCount.collect { } }
        advanceUntilIdle()

        assertEquals(5, viewModel.activeCount.value)
    }

    @Test
    fun `blank manual add is ignored`() = runTest(dispatcher) {
        every { repository.observeAll() } returns flowOf(emptyList())
        every { repository.observeActiveCount() } returns flowOf(0)
        val viewModel = TodoViewModel(repository)

        viewModel.add("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.addManual(any(), any()) }
    }

    @Test
    fun `add complete and delete delegate to the repository`() = runTest(dispatcher) {
        every { repository.observeAll() } returns flowOf(emptyList())
        every { repository.observeActiveCount() } returns flowOf(0)
        val viewModel = TodoViewModel(repository)

        viewModel.add("Walk dog", TodoPriority.LOW)
        viewModel.setCompleted("a", true)
        viewModel.delete("a")
        advanceUntilIdle()

        coVerify { repository.addManual("Walk dog", TodoPriority.LOW) }
        coVerify { repository.setCompleted("a", true) }
        coVerify { repository.delete("a") }
    }
}
