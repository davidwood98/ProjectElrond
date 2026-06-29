package ai.elrond.canvas

import ai.elrond.domain.PageTransform
import ai.elrond.presentation.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * FA-20: the page is a fixed portrait sheet. [CanvasViewModel.pageTransform] centres it (margins in
 * landscape, none in portrait), keeps scale at 1 (no zoom yet), and exposes scroll as a negative Y
 * offset clamped to the page bounds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelPageTransformTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `portrait fills the width with no centring offset`() = runTest(dispatcher) {
        val vm = CanvasViewModel()
        vm.setCanvasSize(1000f, 1600f)
        val t = vm.pageTransform.value
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.offsetX, 0f)
        assertEquals(0f, t.offsetY, 0f)
    }

    @Test
    fun `landscape keeps the portrait width and centres it`() = runTest(dispatcher) {
        val vm = CanvasViewModel()
        // Wider than tall: the page width stays the shorter (portrait) edge = 1000, centred in 1600.
        vm.setCanvasSize(1600f, 1000f)
        val t = vm.pageTransform.value
        assertEquals(1f, t.scale, 0f)
        assertEquals(300f, t.offsetX, 0f) // (1600 - 1000) / 2
    }

    @Test
    fun `scroll offsets Y negatively and clamps to the page bottom`() = runTest(dispatcher) {
        val vm = CanvasViewModel() // default mode is VERTICAL (elastic page-turn overscroll)
        // Portrait page height = 1000 * sqrt(2) ≈ 1414; viewport 1000 tall → maxScroll ≈ 414.
        vm.setCanvasSize(1000f, 1000f)
        val maxScroll = 1000f * PageTransform.ASPECT_RATIO - 1000f

        vm.scrollBy(-100f) // dragging up scrolls the page content up (within bounds)
        assertEquals(-100f, vm.pageTransform.value.offsetY, 0.01f)

        // Over-drag past the bottom pulls elastically; release (no next page here) clamps to maxScroll.
        vm.scrollBy(-100000f)
        vm.releaseScroll()
        assertEquals(-maxScroll, vm.pageTransform.value.offsetY, 0.01f)

        // Over-drag past the top; release clamps back at 0.
        vm.scrollBy(100000f)
        vm.releaseScroll()
        assertEquals(0f, vm.pageTransform.value.offsetY, 0.01f)
    }
}
