package ai.elrond.presentation

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.domain.AppAccent
import ai.elrond.domain.FingerGestureAction
import ai.elrond.domain.NoteTabsMode
import ai.elrond.domain.StylusHoldTool
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.PenIconStyle
import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.domain.UnitSystem
import ai.elrond.data.SettingsRepository
import ai.elrond.domain.TriggerMode
import ai.elrond.data.CalendarProviderType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val triggerCommand: StateFlow<String> = repository.triggerCommand
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TRIGGER)

    val aiNoteSelectedOnCreate: StateFlow<Boolean> = repository.aiNoteSelectedOnCreate
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_AI_NOTE_SELECTED_ON_CREATE,
        )

    /** Returns true if accepted, false if the value was invalid (too long/empty). */
    fun setTriggerCommand(value: String): Boolean {
        val ok = value.isNotBlank() && value.trim().length <= SettingsRepository.MAX_TRIGGER_LENGTH
        if (ok) viewModelScope.launch { repository.setTriggerCommand(value) }
        return ok
    }

    /** Activation method: the written command (`/Q`) or a lasso gesture. */
    val triggerMode: StateFlow<TriggerMode> = repository.triggerMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerMode.COMMAND)

    fun setTriggerMode(mode: TriggerMode) {
        viewModelScope.launch { repository.setTriggerMode(mode) }
    }

    /** Prefix-mode inactivity delay (ms) before the question is sent (default 500). */
    val prefixTriggerDelayMs: StateFlow<Long> = repository.prefixTriggerDelayMs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_PREFIX_TRIGGER_DELAY_MS,
        )

    fun setPrefixTriggerDelayMs(ms: Long) {
        viewModelScope.launch { repository.setPrefixTriggerDelayMs(ms) }
    }

    /** Prefix-mode no-prompt timeout (ms) before an abandoned command reverts to ink (default 2000). */
    val prefixNoPromptTimeoutMs: StateFlow<Long> = repository.prefixNoPromptTimeoutMs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_PREFIX_NO_PROMPT_TIMEOUT_MS,
        )

    fun setPrefixNoPromptTimeoutMs(ms: Long) {
        viewModelScope.launch { repository.setPrefixNoPromptTimeoutMs(ms) }
    }

    /** Palm rejection: when true (default), finger touches never draw. */
    val stylusOnly: StateFlow<Boolean> = repository.stylusOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_STYLUS_ONLY)

    fun setStylusOnly(enabled: Boolean) {
        viewModelScope.launch { repository.setStylusOnly(enabled) }
    }

    // --- Finger gestures (FA-19) ---

    val fingerGesturesEnabled: StateFlow<Boolean> = repository.fingerGesturesEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_FINGER_GESTURES_ENABLED,
        )

    fun setFingerGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setFingerGesturesEnabled(enabled) }
    }

    val twoFingerTapAction: StateFlow<FingerGestureAction> = repository.twoFingerTapAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TWO_FINGER_TAP_ACTION)

    fun setTwoFingerTapAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setTwoFingerTapAction(action) }
    }

    val threeFingerTapAction: StateFlow<FingerGestureAction> = repository.threeFingerTapAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_THREE_FINGER_TAP_ACTION)

    fun setThreeFingerTapAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setThreeFingerTapAction(action) }
    }

    val twoFingerDoubleTapAction: StateFlow<FingerGestureAction> = repository.twoFingerDoubleTapAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION)

    fun setTwoFingerDoubleTapAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setTwoFingerDoubleTapAction(action) }
    }

    val threeFingerDoubleTapAction: StateFlow<FingerGestureAction> = repository.threeFingerDoubleTapAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION)

    fun setThreeFingerDoubleTapAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setThreeFingerDoubleTapAction(action) }
    }

    // --- S Pen button (FA-19) ---

    val stylusButtonEnabled: StateFlow<Boolean> = repository.stylusButtonEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_STYLUS_BUTTON_ENABLED,
        )

    fun setStylusButtonEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setStylusButtonEnabled(enabled) }
    }

    val stylusHoldTool: StateFlow<StylusHoldTool> = repository.stylusHoldTool
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_STYLUS_HOLD_TOOL)

    fun setStylusHoldTool(tool: StylusHoldTool) {
        viewModelScope.launch { repository.setStylusHoldTool(tool) }
    }

    val stylusDoubleClickAction: StateFlow<FingerGestureAction> = repository.stylusDoubleClickAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_STYLUS_DOUBLE_CLICK_ACTION)

    fun setStylusDoubleClickAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setStylusDoubleClickAction(action) }
    }

    val stylusSingleClickAction: StateFlow<FingerGestureAction> = repository.stylusSingleClickAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_STYLUS_SINGLE_CLICK_ACTION)

    fun setStylusSingleClickAction(action: FingerGestureAction) {
        viewModelScope.launch { repository.setStylusSingleClickAction(action) }
    }

    /** Active-tool highlight style shown in the note toolbar (default A · soft tile). */
    val toolSelectedTreatment: StateFlow<ToolSelectedTreatment> = repository.toolSelectedTreatment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolSelectedTreatment.DEFAULT)

    fun setToolSelectedTreatment(treatment: ToolSelectedTreatment) {
        viewModelScope.launch { repository.setToolSelectedTreatment(treatment) }
    }

    /** The user's preferred calendar backend (Device / Google / Outlook). */
    val calendarProvider: StateFlow<CalendarProviderType> = repository.calendarProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarProviderType.DEVICE)

    fun setCalendarProvider(type: CalendarProviderType) {
        viewModelScope.launch { repository.setCalendarProvider(type) }
    }

    // --- Appearance tweaks (FA-14) ---

    /** Pen-family toolbar icon style (whole-tool Body vs writing-Tip). */
    val penIconStyle: StateFlow<PenIconStyle> = repository.penIconStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PenIconStyle.DEFAULT)

    fun setPenIconStyle(style: PenIconStyle) {
        viewModelScope.launch { repository.setPenIconStyle(style) }
    }

    /** App-wide accent colour (Blue / Navy / Green / Pink). */
    val appAccent: StateFlow<AppAccent> = repository.appAccent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppAccent.DEFAULT)

    fun setAppAccent(accent: AppAccent) {
        viewModelScope.launch { repository.setAppAccent(accent) }
    }

    // --- AI mark appearance (FA-17) ---

    /** Which organic loader animates while the AI is thinking (default 17 · cluster). */
    val aiLoaderStyle: StateFlow<AiLoaderStyle> = repository.aiLoaderStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiLoaderStyle.DEFAULT)

    fun setAiLoaderStyle(style: AiLoaderStyle) {
        viewModelScope.launch { repository.setAiLoaderStyle(style) }
    }

    /** Colour treatment for the loader + AI logo (Colour / Black). */
    val aiColorMode: StateFlow<AiColorMode> = repository.aiColorMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiColorMode.DEFAULT)

    fun setAiColorMode(mode: AiColorMode) {
        viewModelScope.launch { repository.setAiColorMode(mode) }
    }

    /** Unit system the AI uses for measurements in its answers (Metric / Imperial). */
    val unitSystem: StateFlow<UnitSystem> = repository.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.DEFAULT)

    fun setUnitSystem(system: UnitSystem) {
        viewModelScope.launch { repository.setUnitSystem(system) }
    }

    /** Note-canvas paper background (Ruled / Plain / Dots). */
    val paperStyle: StateFlow<PaperStyle> = repository.paperStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaperStyle.DEFAULT)

    fun setPaperStyle(style: PaperStyle) {
        viewModelScope.launch { repository.setPaperStyle(style) }
    }

    /** Editor note-tab layout (Attached vs Separate). */
    val noteTabsMode: StateFlow<NoteTabsMode> = repository.noteTabsMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteTabsMode.DEFAULT)

    fun setNoteTabsMode(mode: NoteTabsMode) {
        viewModelScope.launch { repository.setNoteTabsMode(mode) }
    }

    fun setAiNoteSelectedOnCreate(enabled: Boolean) {
        viewModelScope.launch { repository.setAiNoteSelectedOnCreate(enabled) }
    }

    // --- Lasso move snap-back (FA-10) ---

    val lassoSnapBackEnabled: StateFlow<Boolean> = repository.lassoSnapBackEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_LASSO_SNAP_BACK_ENABLED,
        )

    val lassoSnapBackThreshold: StateFlow<Float> = repository.lassoSnapBackThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_LASSO_SNAP_BACK_THRESHOLD,
        )

    /** Sets the threshold; a 0% value also turns the feature off (the slider doubles as an off). */
    fun setLassoSnapBackThreshold(value: Float) {
        viewModelScope.launch {
            repository.setLassoSnapBackThreshold(value)
            if (snapBackDisabledByThreshold(value)) repository.setLassoSnapBackEnabled(false)
        }
    }

    /** Toggles snap-back; turning it on while the threshold is 0 restores a usable default. */
    fun setLassoSnapBackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setLassoSnapBackEnabled(enabled)
            if (enabled) {
                // Read the persisted threshold directly (not the stateIn cache) so the restore is
                // correct regardless of whether the StateFlow currently has a subscriber.
                thresholdToRestoreOnEnable(repository.lassoSnapBackThreshold.first())
                    ?.let { repository.setLassoSnapBackThreshold(it) }
            }
        }
    }

    companion object {
        /** FA-10 coupling rule: a 0% (or lower) threshold also turns the snap-back toggle off. */
        fun snapBackDisabledByThreshold(value: Float): Boolean = value <= 0f

        /**
         * FA-10 coupling rule: when re-enabling snap-back while the stored threshold is 0%, restore
         * a usable default; otherwise leave the threshold unchanged (null = no change).
         */
        fun thresholdToRestoreOnEnable(current: Float): Float? =
            if (current <= 0f) SettingsRepository.DEFAULT_LASSO_SNAP_BACK_THRESHOLD else null
    }

    // --- Auto-extraction (FA-2) ---

    val autoExtractionEnabled: StateFlow<Boolean> = repository.autoExtractionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TRUE)

    val extractionConfirmationEnabled: StateFlow<Boolean> = repository.extractionConfirmationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TRUE)

    val confirmTodoExtraction: StateFlow<Boolean> = repository.confirmTodoExtraction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TRUE)

    val confirmCalendarExtraction: StateFlow<Boolean> = repository.confirmCalendarExtraction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_TRUE)

    fun setAutoExtractionEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoExtractionEnabled(enabled) }
    }

    fun setExtractionConfirmationEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setExtractionConfirmationEnabled(enabled) }
    }

    fun setConfirmTodoExtraction(enabled: Boolean) {
        viewModelScope.launch { repository.setConfirmTodoExtraction(enabled) }
    }

    fun setConfirmCalendarExtraction(enabled: Boolean) {
        viewModelScope.launch { repository.setConfirmCalendarExtraction(enabled) }
    }

    /** True when background extraction added TODO items without a popup — flairs the to-do tab. */
    val hasNewExtractedItems: StateFlow<Boolean> = repository.hasNewExtractedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Clears the flair once the user opens the to-do list. */
    fun markExtractedItemsSeen() {
        viewModelScope.launch { repository.setHasNewExtractedItems(false) }
    }
}
