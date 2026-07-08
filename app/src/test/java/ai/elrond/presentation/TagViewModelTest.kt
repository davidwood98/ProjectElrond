package ai.elrond.presentation

import ai.elrond.data.TagRepository
import ai.elrond.domain.Tag
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The FA-24 untag window — the one genuinely stateful tagging piece: the second tap starts a 2s
 * countdown; cancelling within it means the DB write never happens; letting it elapse removes
 * the membership exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TagRepository>(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cancel before the window elapses leaves the tag assigned (no DB write)`() = runTest(dispatcher) {
        val vm = TagViewModel(repository)
        vm.beginUntag("nb1", "t1")
        runCurrent()
        assertTrue(vm.pendingRemovalKeys.value.contains("nb1:t1"))

        advanceTimeBy(1_500L) // inside the 2s window
        vm.cancelUntag("nb1", "t1")
        advanceTimeBy(5_000L) // give a leaked job every chance to fire
        runCurrent()

        coVerify(exactly = 0) { repository.removeTag(any(), any()) }
        assertTrue(vm.pendingRemovalKeys.value.isEmpty())
    }

    @Test
    fun `an uncancelled window removes the tag after 2 seconds`() = runTest(dispatcher) {
        val vm = TagViewModel(repository)
        vm.beginUntag("nb1", "t1")
        advanceTimeBy(TagViewModel.UNTAG_WINDOW_MS + 1)
        runCurrent()

        coVerify(exactly = 1) { repository.removeTag("nb1", "t1") }
        assertTrue(vm.pendingRemovalKeys.value.isEmpty())
    }

    @Test
    fun `beginUntag is idempotent while a window is already pending`() = runTest(dispatcher) {
        val vm = TagViewModel(repository)
        vm.beginUntag("nb1", "t1")
        advanceTimeBy(500L)
        vm.beginUntag("nb1", "t1") // must NOT restart or double the countdown
        advanceTimeBy(TagViewModel.UNTAG_WINDOW_MS) // well past the first window
        runCurrent()

        coVerify(exactly = 1) { repository.removeTag("nb1", "t1") }
    }

    @Test
    fun `windows for different pills are independent`() = runTest(dispatcher) {
        val vm = TagViewModel(repository)
        vm.beginUntag("nb1", "t1")
        vm.beginUntag("nb1", "t2")
        advanceTimeBy(1_000L)
        vm.cancelUntag("nb1", "t1")
        advanceTimeBy(TagViewModel.UNTAG_WINDOW_MS)
        runCurrent()

        coVerify(exactly = 0) { repository.removeTag("nb1", "t1") }
        coVerify(exactly = 1) { repository.removeTag("nb1", "t2") }
    }

    @Test
    fun `pendingRemovalTagIdsFor scopes keys to one notebook as bare tag ids`() = runTest(dispatcher) {
        every { repository.observeNotebookTags() } returns flowOf(
            mapOf(
                "nb1" to listOf(Tag("t1", "a", 0), Tag("t2", "b", 0)),
                "nb2" to listOf(Tag("t1", "a", 0)), // the same tag on another notebook
            ),
        )
        val vm = TagViewModel(repository)
        vm.beginUntag("nb1", "t1")
        runCurrent()

        assertEquals(setOf("t1"), vm.pendingRemovalTagIdsFor("nb1").first())
        assertTrue(vm.pendingRemovalTagIdsFor("nb2").first().isEmpty())
    }
}
