package ai.elrond.notes

import ai.elrond.data.SettingsRepository
import ai.elrond.data.SubjectRepository
import ai.elrond.domain.Subject
import ai.elrond.presentation.SubjectViewModel
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubjectViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val subjectRepo = mockk<SubjectRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Sensible defaults; individual tests override the selection / subject list.
        every { subjectRepo.observeSubjects() } returns flowOf(emptyList())
        every { subjectRepo.observeNoteSubjects() } returns flowOf(emptyMap())
        every { settings.expandedSubjectIds } returns flowOf(emptySet())
        every { settings.selectedSubjectId } returns flowOf(null)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun subject(id: String, parentId: String? = null, sortOrder: Long = 0L) =
        Subject(id = id, parentId = parentId, name = id, colorId = 0, sortOrder = sortOrder, createdAt = 0, modifiedAt = 0)

    /** Subscribes to the StateFlows so the WhileSubscribed-started pipelines activate. */
    private fun SubjectViewModel.activate(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch { tree.collect {} }
        scope.launch { subjectsById.collect {} }
        scope.launch { selectedSubjectId.collect {} }
        scope.launch { selectedPath.collect {} }
    }

    @Test
    fun `tree reflects repository subjects`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns
            flowOf(listOf(subject("a"), subject("a1", parentId = "a"), subject("b", sortOrder = 1)))
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.tree.value.map { it.subject.id })
        assertEquals(listOf("a1"), vm.tree.value[0].children.map { it.subject.id })
    }

    @Test
    fun `a stale selected id resolves to null`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns flowOf(listOf(subject("a")))
        every { settings.selectedSubjectId } returns flowOf("ghost") // not in the subject list
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        assertNull(vm.selectedSubjectId.value)
        assertEquals(emptyList<String>(), vm.selectedPath.value.map { it.id })
    }

    @Test
    fun `a valid selection exposes its ancestry path`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns flowOf(listOf(subject("a"), subject("a1", parentId = "a")))
        every { settings.selectedSubjectId } returns flowOf("a1")
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        assertEquals("a1", vm.selectedSubjectId.value)
        assertEquals(listOf("a", "a1"), vm.selectedPath.value.map { it.id })
    }

    @Test
    fun `deleting an ancestor of the selected subject resets the selection`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns flowOf(listOf(subject("a"), subject("a1", parentId = "a")))
        every { settings.selectedSubjectId } returns flowOf("a1")
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        vm.deleteSubject("a") // a is an ancestor of the selected a1
        advanceUntilIdle()

        coVerify { settings.setSelectedSubjectId(null) }
        coVerify { subjectRepo.deleteSubject("a") }
    }

    @Test
    fun `deleting an unrelated subject keeps the selection`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns
            flowOf(listOf(subject("a"), subject("a1", parentId = "a"), subject("b", sortOrder = 1)))
        every { settings.selectedSubjectId } returns flowOf("a1")
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        vm.deleteSubject("b")
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setSelectedSubjectId(null) }
        coVerify { subjectRepo.deleteSubject("b") }
    }

    @Test
    fun `moveSubject moves a sibling one step in the drag direction and persists`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns
            flowOf(listOf(subject("a"), subject("b", sortOrder = 1), subject("c", sortOrder = 2)))
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        vm.moveSubject(movedId = "c", up = true) // c moves up one: a, c, b
        advanceUntilIdle()

        coVerify { subjectRepo.reorder(listOf("a", "c", "b")) }
    }

    @Test
    fun `expandToSubject expands every subject on the ancestry path`() = runTest(dispatcher) {
        every { subjectRepo.observeSubjects() } returns flowOf(
            listOf(subject("a"), subject("a1", parentId = "a"), subject("a2", parentId = "a1")),
        )
        val vm = SubjectViewModel(subjectRepo, settings)
        vm.activate(backgroundScope)
        advanceUntilIdle()

        vm.expandToSubject("a2")
        advanceUntilIdle()

        coVerify { settings.expandSubjects(setOf("a", "a1", "a2")) }
    }

    @Test
    fun `createSubject delegates and expands the parent`() = runTest(dispatcher) {
        val vm = SubjectViewModel(subjectRepo, settings)

        vm.createSubject(parentId = "a", name = "Child")
        advanceUntilIdle()

        coVerify { subjectRepo.createSubject("a", "Child") }
        coVerify { settings.setSubjectExpanded("a", true) }
    }
}
