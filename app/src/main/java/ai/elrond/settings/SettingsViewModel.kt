package ai.elrond.settings

import ai.elrond.ai.TriggerMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** Palm rejection: when true (default), finger touches never draw. */
    val stylusOnly: StateFlow<Boolean> = repository.stylusOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_STYLUS_ONLY)

    fun setStylusOnly(enabled: Boolean) {
        viewModelScope.launch { repository.setStylusOnly(enabled) }
    }

    fun setAiNoteSelectedOnCreate(enabled: Boolean) {
        viewModelScope.launch { repository.setAiNoteSelectedOnCreate(enabled) }
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
