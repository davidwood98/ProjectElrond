package ai.elrond.settings

import ai.elrond.data.SettingsRepository
import ai.elrond.presentation.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FA-10 snap-back coupling rules, pure (no DataStore / Robolectric): the slider doubling as an
 * off-switch at 0%, and re-enabling restoring a usable default. The [SettingsViewModel] setters wire
 * these into the (async) DataStore writes; this verifies the decisions themselves deterministically.
 */
class SettingsSnapBackCouplingTest {

    @Test
    fun `a zero threshold turns the toggle off`() {
        assertTrue(SettingsViewModel.snapBackDisabledByThreshold(0f))
    }

    @Test
    fun `a negative threshold also turns the toggle off`() {
        assertTrue(SettingsViewModel.snapBackDisabledByThreshold(-0.01f))
    }

    @Test
    fun `a positive threshold leaves the toggle on`() {
        assertFalse(SettingsViewModel.snapBackDisabledByThreshold(0.025f))
    }

    @Test
    fun `enabling while the threshold is zero restores the default`() {
        assertEquals(
            SettingsRepository.DEFAULT_LASSO_SNAP_BACK_THRESHOLD,
            SettingsViewModel.thresholdToRestoreOnEnable(0f),
        )
    }

    @Test
    fun `enabling while the threshold is positive leaves it unchanged`() {
        assertNull(SettingsViewModel.thresholdToRestoreOnEnable(0.05f))
    }
}
