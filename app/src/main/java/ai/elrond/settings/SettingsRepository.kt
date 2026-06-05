package ai.elrond.settings

import ai.elrond.calendar.CalendarProviderType
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    companion object {
        const val DEFAULT_TRIGGER = "/Q"
        const val MAX_TRIGGER_LENGTH = 2
        private val TRIGGER_KEY = stringPreferencesKey("trigger_command")
        private val CALENDAR_PROVIDER_KEY = stringPreferencesKey("calendar_provider")
    }
}
