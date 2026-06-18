package ai.elrond.notes

import ai.elrond.calendar.CalendarEvent
import ai.elrond.calendar.CalendarNotAuthenticatedException
import ai.elrond.calendar.CalendarProviderType
import ai.elrond.calendar.CalendarProviders
import ai.elrond.calendar.DateRange
import ai.elrond.calendar.OutlookAuthProvider
import ai.elrond.calendar.OutlookAuthState
import ai.elrond.settings.SettingsRepository
import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Events tab renders for the currently selected calendar provider. */
sealed interface EventsUiState {
    data object Loading : EventsUiState

    /** Outlook is selected but this build has no Azure client id. */
    data object NotConfigured : EventsUiState

    /** Outlook is selected and configured, but no account is signed in — show the sign-in CTA. */
    data object NeedsSignIn : EventsUiState

    /** Upcoming events for [providerType]; [signedInAs] is the Outlook account when known. */
    data class Events(
        val providerType: CalendarProviderType,
        val signedInAs: String?,
        val events: List<CalendarEvent>,
    ) : EventsUiState

    data class Error(val message: String, val providerType: CalendarProviderType) : EventsUiState
}

/**
 * Backs the Calendar → Events tab: resolves the user's selected [CalendarProviderType], reflects the
 * Outlook auth state, and loads upcoming events from the corresponding [ai.elrond.calendar.CalendarProvider].
 *
 * No Graph/MSAL types appear in this ViewModel — it goes through the [OutlookAuthProvider] seam and a
 * [loadEvents] lambda (the architectural rule that provider APIs stay behind their interfaces). The
 * primary constructor takes test seams (flows + lambda); the [Inject] secondary wires production deps,
 * mirroring CanvasViewModel/CalendarViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EventsViewModel(
    private val providerTypeFlow: Flow<CalendarProviderType>,
    private val outlookAuth: OutlookAuthProvider,
    private val loadEvents: suspend (CalendarProviderType, DateRange) -> Result<List<CalendarEvent>>,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowDays: Long = DEFAULT_WINDOW_DAYS,
) : ViewModel() {

    @Inject
    constructor(
        settings: SettingsRepository,
        outlookAuth: OutlookAuthProvider,
        providers: CalendarProviders,
    ) : this(
        providerTypeFlow = settings.calendarProvider,
        outlookAuth = outlookAuth,
        loadEvents = { type, range -> providers.forType(type).getEvents(range) },
    )

    /** Bumped to re-run the load (after sign-in/out or a manual retry). */
    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<EventsUiState> =
        combine(providerTypeFlow, outlookAuth.state, refresh) { type, auth, _ -> type to auth }
            .flatMapLatest { (type, auth) ->
                flow {
                    emit(EventsUiState.Loading)
                    emit(resolve(type, auth))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsUiState.Loading)

    private suspend fun resolve(type: CalendarProviderType, auth: OutlookAuthState): EventsUiState {
        if (type == CalendarProviderType.OUTLOOK) {
            when (auth) {
                is OutlookAuthState.NotConfigured -> return EventsUiState.NotConfigured
                is OutlookAuthState.SignedOut -> return EventsUiState.NeedsSignIn
                is OutlookAuthState.SignedIn -> Unit // fall through to load
            }
        }
        val start = now()
        val range = DateRange(start, start + windowDays * MILLIS_PER_DAY)
        return loadEvents(type, range).fold(
            onSuccess = { events ->
                EventsUiState.Events(
                    providerType = type,
                    signedInAs = (auth as? OutlookAuthState.SignedIn)?.username,
                    events = events.sortedBy { it.startTime },
                )
            },
            onFailure = { error ->
                // For Outlook, a "needs auth" failure means the session lapsed — offer sign-in again.
                if (type == CalendarProviderType.OUTLOOK && error is CalendarNotAuthenticatedException) {
                    EventsUiState.NeedsSignIn
                } else {
                    EventsUiState.Error(error.message ?: "Couldn't load events.", type)
                }
            },
        )
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            outlookAuth.signIn(activity)
            refresh.value++
        }
    }

    fun signOut() {
        viewModelScope.launch {
            outlookAuth.signOut()
            refresh.value++
        }
    }

    fun retry() {
        refresh.value++
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
