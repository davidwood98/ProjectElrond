package ai.elrond.settings

import ai.elrond.ai.TriggerMode
import ai.elrond.calendar.CalendarProviderType
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App settings backed by DataStore. Currently holds the (debug) `/Q` activation
 * command so it can be changed to e.g. ">Q" or "@Q".
 */
class SettingsRepository(private val context: Context) {

    /** The activation command the canvas watches for. Defaults to [DEFAULT_TRIGGER]. */
    val triggerCommand: Flow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[TRIGGER_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_TRIGGER }

    /** Stores a new trigger; rejects anything longer than [MAX_TRIGGER_LENGTH]. */
    suspend fun setTriggerCommand(value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_TRIGGER_LENGTH) {
            "Trigger must be 1–$MAX_TRIGGER_LENGTH characters"
        }
        context.settingsDataStore.edit { it[TRIGGER_KEY] = trimmed }
    }

    /** How the AI is activated on the canvas: the written command, or a lasso gesture. */
    val triggerMode: Flow<TriggerMode> = context.settingsDataStore.data
        .map { prefs -> TriggerMode.fromName(prefs[TRIGGER_MODE_KEY]) }

    suspend fun setTriggerMode(mode: TriggerMode) {
        context.settingsDataStore.edit { it[TRIGGER_MODE_KEY] = mode.name }
    }

    /** Palm rejection: when true (default), finger touches never draw ink. */
    val stylusOnly: Flow<Boolean> = context.settingsDataStore.data
        .map { it[STYLUS_ONLY_KEY] ?: DEFAULT_STYLUS_ONLY }

    suspend fun setStylusOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[STYLUS_ONLY_KEY] = enabled }
    }

    /** The user's preferred calendar backend (default DEVICE). */
    val calendarProvider: Flow<CalendarProviderType> = context.settingsDataStore.data
        .map { prefs ->
            prefs[CALENDAR_PROVIDER_KEY]
                ?.let { runCatching { CalendarProviderType.valueOf(it) }.getOrNull() }
                ?: CalendarProviderType.DEVICE
        }

    suspend fun setCalendarProvider(type: CalendarProviderType) {
        context.settingsDataStore.edit { it[CALENDAR_PROVIDER_KEY] = type.name }
    }

    /**
     * Whether a freshly created AI response starts in the selected/edit state (move,
     * resize, delete; tap off to place it). When off it lands deselected, in the note
     * flow, and a long-press selects it. Default true.
     */
    val aiNoteSelectedOnCreate: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[AI_NOTE_SELECTED_ON_CREATE_KEY] ?: DEFAULT_AI_NOTE_SELECTED_ON_CREATE }

    suspend fun setAiNoteSelectedOnCreate(enabled: Boolean) {
        context.settingsDataStore.edit { it[AI_NOTE_SELECTED_ON_CREATE_KEY] = enabled }
    }

    // --- Background auto-extraction (FA-2) ---

    /** Master switch: run TODO/calendar extraction in the background after a note is saved. */
    val autoExtractionEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[AUTO_EXTRACTION_KEY] ?: DEFAULT_TRUE }

    suspend fun setAutoExtractionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_EXTRACTION_KEY] = enabled }
    }

    /** Global confirmation switch: show the on-canvas Yes/No popup for detected items. */
    val extractionConfirmationEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[EXTRACTION_CONFIRM_KEY] ?: DEFAULT_TRUE }

    suspend fun setExtractionConfirmationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[EXTRACTION_CONFIRM_KEY] = enabled }
    }

    /** Per-type confirmation (only meaningful while [extractionConfirmationEnabled]). */
    val confirmTodoExtraction: Flow<Boolean> = context.settingsDataStore.data
        .map { it[CONFIRM_TODO_KEY] ?: DEFAULT_TRUE }

    suspend fun setConfirmTodoExtraction(enabled: Boolean) {
        context.settingsDataStore.edit { it[CONFIRM_TODO_KEY] = enabled }
    }

    val confirmCalendarExtraction: Flow<Boolean> = context.settingsDataStore.data
        .map { it[CONFIRM_CALENDAR_KEY] ?: DEFAULT_TRUE }

    suspend fun setConfirmCalendarExtraction(enabled: Boolean) {
        context.settingsDataStore.edit { it[CONFIRM_CALENDAR_KEY] = enabled }
    }

    /**
     * Set true by the background job when it auto-adds TODO items without a confirmation
     * popup, so the UI can flair the to-do tab; cleared when the user opens the TODO panel.
     */
    val hasNewExtractedItems: Flow<Boolean> = context.settingsDataStore.data
        .map { it[NEW_EXTRACTED_ITEMS_KEY] ?: false }

    suspend fun setHasNewExtractedItems(value: Boolean) {
        context.settingsDataStore.edit { it[NEW_EXTRACTED_ITEMS_KEY] = value }
    }

    companion object {
        const val DEFAULT_TRIGGER = "/Q"
        const val MAX_TRIGGER_LENGTH = 2
        const val DEFAULT_AI_NOTE_SELECTED_ON_CREATE = true
        const val DEFAULT_STYLUS_ONLY = true
        const val DEFAULT_TRUE = true
        private val TRIGGER_KEY = stringPreferencesKey("trigger_command")
        private val TRIGGER_MODE_KEY = stringPreferencesKey("trigger_mode")
        private val STYLUS_ONLY_KEY = booleanPreferencesKey("stylus_only")
        private val CALENDAR_PROVIDER_KEY = stringPreferencesKey("calendar_provider")
        private val AI_NOTE_SELECTED_ON_CREATE_KEY = booleanPreferencesKey("ai_note_selected_on_create")
        private val AUTO_EXTRACTION_KEY = booleanPreferencesKey("auto_extraction_enabled")
        private val EXTRACTION_CONFIRM_KEY = booleanPreferencesKey("extraction_confirmation_enabled")
        private val CONFIRM_TODO_KEY = booleanPreferencesKey("confirm_todo_extraction")
        private val CONFIRM_CALENDAR_KEY = booleanPreferencesKey("confirm_calendar_extraction")
        private val NEW_EXTRACTED_ITEMS_KEY = booleanPreferencesKey("has_new_extracted_items")
    }
}
