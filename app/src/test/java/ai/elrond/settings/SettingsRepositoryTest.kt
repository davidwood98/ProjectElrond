package ai.elrond.settings

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.domain.AppAccent
import ai.elrond.domain.FingerGestureAction
import ai.elrond.domain.NoteTabsMode
import ai.elrond.domain.StylusHoldTool
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.PenIconStyle
import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.data.SettingsRepository
import ai.elrond.domain.TriggerMode
import ai.elrond.domain.UnitSystem
import ai.elrond.data.CalendarProviderType
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trips the FA-2 auto-extraction preferences through DataStore (Robolectric). One
 * test method so a single DataStore instance backs the whole class (avoids the "multiple
 * DataStores for the same file" guard across instances).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `auto-extraction preferences round-trip and default on`() = runTest {
        // Defaults (fresh store): auto-extraction + confirmations on, new-items flag off.
        assertTrue(repo.autoExtractionEnabled.first())
        assertTrue(repo.extractionConfirmationEnabled.first())
        assertTrue(repo.confirmTodoExtraction.first())
        assertTrue(repo.confirmCalendarExtraction.first())
        assertFalse(repo.hasNewExtractedItems.first())

        repo.setAutoExtractionEnabled(false)
        repo.setExtractionConfirmationEnabled(false)
        repo.setConfirmTodoExtraction(false)
        repo.setConfirmCalendarExtraction(false)
        repo.setHasNewExtractedItems(true)

        assertFalse(repo.autoExtractionEnabled.first())
        assertFalse(repo.extractionConfirmationEnabled.first())
        assertFalse(repo.confirmTodoExtraction.first())
        assertFalse(repo.confirmCalendarExtraction.first())
        assertTrue(repo.hasNewExtractedItems.first())

        repo.setAutoExtractionEnabled(true)
        repo.setHasNewExtractedItems(false)
        assertTrue(repo.autoExtractionEnabled.first())
        assertFalse(repo.hasNewExtractedItems.first())

        // FA-4 activation + palm-rejection prefs: defaults, then round-trip.
        assertEquals("/Q", repo.triggerCommand.first())
        assertEquals(TriggerMode.COMMAND, repo.triggerMode.first())
        assertTrue(repo.stylusOnly.first())

        repo.setTriggerCommand(">Q")
        repo.setTriggerMode(TriggerMode.GESTURE)
        repo.setStylusOnly(false)

        assertEquals(">Q", repo.triggerCommand.first())
        assertEquals(TriggerMode.GESTURE, repo.triggerMode.first())
        assertFalse(repo.stylusOnly.first())

        // FA-19 finger gestures: defaults (on; 1×2 Undo, 1×3 Redo, 2×2 last-tool, 2×3 none),
        // then round-trip each preference.
        assertTrue(repo.fingerGesturesEnabled.first())
        assertEquals(FingerGestureAction.UNDO, repo.twoFingerTapAction.first())
        assertEquals(FingerGestureAction.REDO, repo.threeFingerTapAction.first())
        assertEquals(FingerGestureAction.LAST_TOOL_SWAP, repo.twoFingerDoubleTapAction.first())
        assertEquals(FingerGestureAction.NONE, repo.threeFingerDoubleTapAction.first())

        repo.setFingerGesturesEnabled(false)
        repo.setTwoFingerTapAction(FingerGestureAction.SELECT_PEN)
        repo.setThreeFingerTapAction(FingerGestureAction.SELECT_ERASER)
        repo.setTwoFingerDoubleTapAction(FingerGestureAction.SELECT_HAND)
        repo.setThreeFingerDoubleTapAction(FingerGestureAction.REDO)

        assertFalse(repo.fingerGesturesEnabled.first())
        assertEquals(FingerGestureAction.SELECT_PEN, repo.twoFingerTapAction.first())
        assertEquals(FingerGestureAction.SELECT_ERASER, repo.threeFingerTapAction.first())
        assertEquals(FingerGestureAction.SELECT_HAND, repo.twoFingerDoubleTapAction.first())
        assertEquals(FingerGestureAction.REDO, repo.threeFingerDoubleTapAction.first())

        // FA-19 S Pen button: defaults (on; hold→Eraser, double→Lasso, single→none), then round-trip.
        assertTrue(repo.stylusButtonEnabled.first())
        assertEquals(StylusHoldTool.ERASER, repo.stylusHoldTool.first())
        assertEquals(FingerGestureAction.SELECT_LASSO, repo.stylusDoubleClickAction.first())
        assertEquals(FingerGestureAction.NONE, repo.stylusSingleClickAction.first())

        repo.setStylusButtonEnabled(false)
        repo.setStylusHoldTool(StylusHoldTool.LASSO)
        repo.setStylusDoubleClickAction(FingerGestureAction.UNDO)
        repo.setStylusSingleClickAction(FingerGestureAction.SELECT_PEN)

        assertFalse(repo.stylusButtonEnabled.first())
        assertEquals(StylusHoldTool.LASSO, repo.stylusHoldTool.first())
        assertEquals(FingerGestureAction.UNDO, repo.stylusDoubleClickAction.first())
        assertEquals(FingerGestureAction.SELECT_PEN, repo.stylusSingleClickAction.first())

        // Prefix-mode trigger mode round-trips (third activation option).
        repo.setTriggerMode(TriggerMode.PREFIX_COMMAND)
        assertEquals(TriggerMode.PREFIX_COMMAND, repo.triggerMode.first())

        // Prefix-mode delays: defaults (0.5s / 2s), round-trip, and clamp to range.
        assertEquals(500L, repo.prefixTriggerDelayMs.first())
        assertEquals(2_000L, repo.prefixNoPromptTimeoutMs.first())

        repo.setPrefixTriggerDelayMs(800L)
        assertEquals(800L, repo.prefixTriggerDelayMs.first())
        repo.setPrefixTriggerDelayMs(50L) // below the 200ms floor
        assertEquals(200L, repo.prefixTriggerDelayMs.first())
        repo.setPrefixTriggerDelayMs(9_999L) // above the 3000ms ceiling
        assertEquals(3_000L, repo.prefixTriggerDelayMs.first())

        repo.setPrefixNoPromptTimeoutMs(5_000L)
        assertEquals(5_000L, repo.prefixNoPromptTimeoutMs.first())
        repo.setPrefixNoPromptTimeoutMs(100L) // below the 1s floor
        assertEquals(1_000L, repo.prefixNoPromptTimeoutMs.first())
        repo.setPrefixNoPromptTimeoutMs(99_999L) // above the 10s ceiling
        assertEquals(10_000L, repo.prefixNoPromptTimeoutMs.first())

        // FA-10 lasso snap-back: defaults (2.5%, on), then round-trip and persist.
        assertEquals(0.025f, repo.lassoSnapBackThreshold.first(), 1e-4f)
        assertTrue(repo.lassoSnapBackEnabled.first())

        repo.setLassoSnapBackThreshold(0.05f)
        repo.setLassoSnapBackEnabled(false)

        assertEquals(0.05f, repo.lassoSnapBackThreshold.first(), 1e-4f)
        assertFalse(repo.lassoSnapBackEnabled.first())

        // Out-of-range threshold is clamped to the 0–10% range.
        repo.setLassoSnapBackThreshold(0.5f)
        assertEquals(0.10f, repo.lassoSnapBackThreshold.first(), 1e-4f)

        // Debug stroke-simplification spacing: default off (0), round-trips, clamps to 0–12.
        assertEquals(0f, repo.strokeSimplificationSpacing.first(), 1e-4f)
        repo.setStrokeSimplificationSpacing(4.5f)
        assertEquals(4.5f, repo.strokeSimplificationSpacing.first(), 1e-4f)
        repo.setStrokeSimplificationSpacing(99f)
        assertEquals(12f, repo.strokeSimplificationSpacing.first(), 1e-4f)

        // FA-11 calendar provider: default DEVICE, round-trips to OUTLOOK.
        assertEquals(CalendarProviderType.DEVICE, repo.calendarProvider.first())
        repo.setCalendarProvider(CalendarProviderType.OUTLOOK)
        assertEquals(CalendarProviderType.OUTLOOK, repo.calendarProvider.first())

        // FA-13 selected-tool treatment: default SOFT_TILE, round-trips to FILLED / UNDERLINE.
        assertEquals(ToolSelectedTreatment.SOFT_TILE, repo.toolSelectedTreatment.first())
        repo.setToolSelectedTreatment(ToolSelectedTreatment.FILLED)
        assertEquals(ToolSelectedTreatment.FILLED, repo.toolSelectedTreatment.first())
        repo.setToolSelectedTreatment(ToolSelectedTreatment.UNDERLINE)
        assertEquals(ToolSelectedTreatment.UNDERLINE, repo.toolSelectedTreatment.first())

        // FA-14 appearance tweaks: defaults, then round-trip.
        assertEquals(PenIconStyle.BODY, repo.penIconStyle.first())
        assertEquals(AppAccent.BLUE, repo.appAccent.first())
        assertEquals(PaperStyle.DOTS, repo.paperStyle.first())
        assertEquals(NoteTabsMode.SEPARATE, repo.noteTabsMode.first())
        // FA-20 scroll direction: default Vertical, then round-trip.
        assertEquals(PageNavigationMode.VERTICAL, repo.pageNavigationMode.first())

        repo.setPenIconStyle(PenIconStyle.TIP)
        repo.setAppAccent(AppAccent.PINK)
        repo.setPaperStyle(PaperStyle.RULED)
        repo.setNoteTabsMode(NoteTabsMode.ATTACHED)
        repo.setPageNavigationMode(PageNavigationMode.HORIZONTAL)

        assertEquals(PenIconStyle.TIP, repo.penIconStyle.first())
        assertEquals(AppAccent.PINK, repo.appAccent.first())
        assertEquals(PaperStyle.RULED, repo.paperStyle.first())
        assertEquals(NoteTabsMode.ATTACHED, repo.noteTabsMode.first())
        assertEquals(PageNavigationMode.HORIZONTAL, repo.pageNavigationMode.first())

        // FA-17 AI-mark appearance: defaults (17c cluster + colour), then round-trip.
        assertEquals(AiLoaderStyle.CLUSTER, repo.aiLoaderStyle.first())
        assertEquals(AiColorMode.COLOR, repo.aiColorMode.first())

        repo.setAiLoaderStyle(AiLoaderStyle.PINCH)
        repo.setAiColorMode(AiColorMode.BLACK)
        assertEquals(AiLoaderStyle.PINCH, repo.aiLoaderStyle.first())
        assertEquals(AiColorMode.BLACK, repo.aiColorMode.first())

        // AI response units: default Metric, round-trips to Imperial and back.
        assertEquals(UnitSystem.METRIC, repo.unitSystem.first())
        repo.setUnitSystem(UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, repo.unitSystem.first())
        repo.setUnitSystem(UnitSystem.METRIC)
        assertEquals(UnitSystem.METRIC, repo.unitSystem.first())

        // FA-16 subjects sidebar state: defaults (nothing expanded, no selection), then round-trip.
        assertTrue(repo.expandedSubjectIds.first().isEmpty())
        assertNull(repo.selectedSubjectId.first())

        repo.setSubjectExpanded("s1", true)
        repo.setSubjectExpanded("s2", true)
        repo.setSubjectExpanded("s1", false) // collapsing removes it
        assertEquals(setOf("s2"), repo.expandedSubjectIds.first())

        // Batch expand (used by the Quick Nav "current note" locator) unions the ids in.
        repo.expandSubjects(setOf("s3", "s4"))
        assertEquals(setOf("s2", "s3", "s4"), repo.expandedSubjectIds.first())

        repo.setSelectedSubjectId("s2")
        assertEquals("s2", repo.selectedSubjectId.first())
        repo.setSelectedSubjectId(null)
        assertNull(repo.selectedSubjectId.first())
    }
}
