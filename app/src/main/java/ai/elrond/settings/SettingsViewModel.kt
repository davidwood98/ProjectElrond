package ai.elrond.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
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

    fun setAiNoteSelectedOnCreate(enabled: Boolean) {
        viewModelScope.launch { repository.setAiNoteSelectedOnCreate(enabled) }
    }
}

fun settingsViewModelFactory(repository: SettingsRepository): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { SettingsViewModel(repository) }
    }
