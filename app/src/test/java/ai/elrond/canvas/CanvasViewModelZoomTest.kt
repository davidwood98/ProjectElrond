package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FA-20 pinch zoom: [CanvasViewModel.zoomAndPan] scales the page (clamped to 50–400%), [endPinch]
 * snaps to the nearest of 100% / fit-width when close, and horizontal pan is only enabled once the
 * zoomed page is wider than the viewport. The default scroll mode is VERTICAL, so a horizontal swipe
 * is a no-op (you scroll between pages instead).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelZoomTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CanvasViewModel().apply { setCanvasSize(1000f, 1600f) } // portrait

    @Test
    fun `zoom clamps to the max factor`() = runTest(dispatcher) {
        val vm = vm()
        vm.zoomAndPan(focalX = 500f, focalY = 800f, scaleFactor = 100f, panDx = 0f, panDy = 0f)
        assertEquals(CanvasViewModel.MAX_ZOOM, vm.pageTransform.value.scale, 0f)
    }

    @Test
    fun `zoom clamps to the min factor`() = runTest(dispatcher) {
        val vm = vm()
        vm.zoomAndPan(focalX = 500f, focalY = 800f, scaleFactor = 0.0001f, panDx = 0f, panDy = 0f)
        assertEquals(CanvasViewModel.MIN_ZOOM, vm.pageTransform.value.scale, 0f)
    }

    @Test
    fun `release near 100 percent snaps to 100 percent`() = runTest(dispatcher) {
        val vm = vm()
        vm.zoomAndPan(focalX = 500f, focalY = 800f, scaleFactor = 1.05f, panDx = 0f, panDy = 0f)
        vm.endPinch()
        assertEquals(1f, vm.pageTransform.value.scale, 0.0001f)
        assertTrue(vm.zoomSnapped.value)
    }

    @Test
    fun `release far from a snap target keeps the zoom`() = runTest(dispatcher) {
        val vm = vm()
        vm.zoomAndPan(focalX = 500f, focalY = 800f, scaleFactor = 2f, panDx = 0f, panDy = 0f)
        vm.endPinch()
        assertEquals(2f, vm.pageTransform.value.scale, 0.0001f)
        assertFalse(vm.zoomSnapped.value)
    }

    @Test
    fun `landscape snaps to fit-width`() = runTest(dispatcher) {
        val vm = CanvasViewModel().apply { setCanvasSize(1600f, 1000f) } // landscape, page width = 1000
        // fit-width = 1600 / 1000 = 1.6×; release near it should snap exactly.
        vm.zoomAndPan(focalX = 800f, focalY = 500f, scaleFactor = 1.55f, panDx = 0f, panDy = 0f)
        vm.endPinch()
        assertEquals(1.6f, vm.pageTransform.value.scale, 0.0001f)
        assertTrue(vm.zoomSnapped.value)
    }

    @Test
    fun `horizontal pan is enabled only once zoomed wider than the viewport`() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.canPanHorizontally()) // 100%: page width == screen width
        vm.zoomAndPan(focalX = 500f, focalY = 800f, scaleFactor = 2f, panDx = 0f, panDy = 0f)
        assertTrue(vm.canPanHorizontally())
    }

    @Test
    fun `horizontal swipe is a no-op in vertical scroll mode`() = runTest(dispatcher) {
        val vm = vm() // default mode is VERTICAL
        vm.swipeBy(120f)
        assertEquals(0f, vm.pageTransform.value.panX, 0f)
    }
}
