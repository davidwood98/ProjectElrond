package ai.elrond.settings

import ai.elrond.domain.AppAccent
import ai.elrond.domain.NoteTabsMode
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.PenIconStyle
import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.data.SettingsRepository
import ai.elrond.domain.TriggerMode
import ai.elrond.data.CalendarProviderType
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        repo.setPenIconStyle(PenIconStyle.TIP)
        repo.setAppAccent(AppAccent.PINK)
        repo.setPaperStyle(PaperStyle.RULED)
        repo.setNoteTabsMode(NoteTabsMode.ATTACHED)

        assertEquals(PenIconStyle.TIP, repo.penIconStyle.first())
        assertEquals(AppAccent.PINK, repo.appAccent.first())
        assertEquals(PaperStyle.RULED, repo.paperStyle.first())
        assertEquals(NoteTabsMode.ATTACHED, repo.noteTabsMode.first())
    }
}
